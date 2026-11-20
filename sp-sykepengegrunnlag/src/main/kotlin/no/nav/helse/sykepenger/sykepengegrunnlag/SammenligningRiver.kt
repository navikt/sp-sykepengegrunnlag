package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import kotliquery.sessionOf
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.loggWarn
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** Sammenligner det nyeste grunnlaget med `sykepengegrunnlagsfakta` i godkjenningsbehovet fra Spleis mens appen er i skyggedrift. */
internal class SammenligningRiver(
    rapidsConnection: RapidsConnection,
    private val dataSource: DataSource,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition {
                    it.requireValue("@event_name", "behov")
                    it.requireAllOrAny("@behov", listOf(BEHOV))
                    it.forbid("@løsning")
                }
                validate {
                    it.require("fødselsnummer") { node -> Identitetsnummer.fraString(node.asString()) }
                    it.require("$BEHOV.skjæringstidspunkt", JsonNode::asLocalDate)
                    it.require("$BEHOV.vilkårsgrunnlagId") { node -> UUID.fromString(node.asString()) }
                    it.requireKey("$BEHOV.sykepengegrunnlagsfakta.sykepengegrunnlag", "$BEHOV.sykepengegrunnlagsfakta.6G", "$BEHOV.sykepengegrunnlagsfakta.fastsatt")
                    it.interestedIn("$BEHOV.sykepengegrunnlagsfakta")
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val fødselsnummer = Identitetsnummer.fraString(packet["fødselsnummer"].asString())
        val skjæringstidspunkt = packet["$BEHOV.skjæringstidspunkt"].asLocalDate()
        val vilkårsgrunnlagId = packet["$BEHOV.vilkårsgrunnlagId"].asString()

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer.verdi) {
            try {
                val spleis = packet["$BEHOV.sykepengegrunnlagsfakta"].tilSykepengegrunnlagsfakta()
                val sammenligning = Sammenligning.sammenlign(finnNyesteUtenomBackfillOrNull(fødselsnummer, skjæringstidspunkt), spleis)
                meterRegistry.counter(METRIKK, "utfall", sammenligning.utfall).increment()
                logg(sammenligning, vilkårsgrunnlagId, skjæringstidspunkt)
            } catch (ex: Exception) {
                loggError(
                    "Feil under sammenligning av sykepengegrunnlaget med Spleis",
                    ex,
                    "vilkårsgrunnlagId" to vilkårsgrunnlagId,
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
            }
        }
    }

    private fun logg(
        sammenligning: Sammenligning,
        vilkårsgrunnlagId: String,
        skjæringstidspunkt: LocalDate,
    ) {
        when (sammenligning) {
            Sammenligning.Mangler ->
                loggInfo(
                    "Sammenligning i skyggedrift: sykepengegrunnlaget mangler",
                    "vilkårsgrunnlagId" to vilkårsgrunnlagId,
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )

            is Sammenligning.Lik ->
                loggInfo(
                    "Sammenligning i skyggedrift: sykepengegrunnlaget er likt",
                    "vilkårsgrunnlagId" to vilkårsgrunnlagId,
                    "sykepengegrunnlagId" to
                        sammenligning.grunnlag.id.uuid
                            .toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )

            is Sammenligning.Forskjellig ->
                loggWarn(
                    "Sammenligning i skyggedrift: forskjell i skyggedrift",
                    "vilkårsgrunnlagId" to vilkårsgrunnlagId,
                    "sykepengegrunnlagId" to
                        sammenligning.grunnlag.id.uuid
                            .toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    "forskjeller" to sammenligning.forskjeller.joinToString("; "),
                    "månederFraAOrdningen" to
                        sammenligning.månederFraAOrdningenForArbeidsgivereMedForskjell
                            .takeIf { it.isNotEmpty() }
                            ?.entries
                            ?.joinToString("; ") { (orgnummer, måneder) ->
                                "${orgnummer.verdi}: [${måneder.joinToString { "${it.måned}=${it.beløp.toPlainString()}" }}]"
                            },
                )
        }
    }

    private fun finnNyesteUtenomBackfillOrNull(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
    ) = sessionOf(dataSource).use { session ->
        session.transaction { tx -> PgSykepengegrunnlagRepository(tx).finnNyesteUtenomBackfillOrNull(fødselsnummer, skjæringstidspunkt) }
    }

    private fun JsonNode.tilSykepengegrunnlagsfakta() =
        Sykepengegrunnlagsfakta(
            sykepengegrunnlag = Sykepengegrunnlagsfakta.beløp(path("sykepengegrunnlag").decimalValue()),
            seksG = Sykepengegrunnlagsfakta.beløp(path("6G").decimalValue()),
            fastsatt = path("fastsatt").asString(),
            arbeidsgivere =
                path("arbeidsgivere").toList().map {
                    Sykepengegrunnlagsfakta.Arbeidsgiver(
                        orgnummer = Organisasjonsnummer.fraString(it.path("arbeidsgiver").asString()),
                        omregnetÅrsinntekt = Sykepengegrunnlagsfakta.beløp(it.path("omregnetÅrsinntekt").decimalValue()),
                        inntektskilde = it.path("inntektskilde").asString(),
                        skjønnsfastsatt = it.path("skjønnsfastsatt").takeIf(JsonNode::isNumber)?.let { beløp -> Sykepengegrunnlagsfakta.beløp(beløp.decimalValue()) },
                    )
                },
            pensjonsgivendeInntekter =
                path("selvstendig").path("pensjonsgivendeInntekter").toList().map {
                    Sykepengegrunnlagsfakta.PensjonsgivendeInntekt(
                        år = it.path("årstall").asInt(),
                        beløp = Sykepengegrunnlagsfakta.beløp(it.path("beløp").decimalValue()),
                    )
                },
        )

    private companion object {
        const val BEHOV = "Godkjenning"
        const val METRIKK = "sykepengegrunnlag_sammenligning_i_skyggedrift"
    }
}
