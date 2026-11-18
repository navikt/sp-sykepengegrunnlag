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
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

internal class SykepengegrunnlagRiver(
    rapidsConnection: RapidsConnection,
    private val inntektsmeldinger: Inntektsmeldinger,
    private val søknader: Søknader,
    private val rettighetskategoriClient: RettighetskategoriClient,
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
                    it.require("$BEHOV.rettighetskategoriId") { node -> UUID.fromString(node.asString()) }
                    it.requireArray("$BEHOV.arbeidsgivere") {
                        requireKey("orgnummer", "inntektskilde")
                        interestedIn("inntektsmeldingMeldingsreferanseId")
                    }
                    it.interestedIn("$BEHOV.selvstendig.søknadMeldingsreferanseId") { node -> UUID.fromString(node.asString()) }
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
        val rettighetskategoriId = RettighetskategoriId(UUID.fromString(packet["$BEHOV.rettighetskategoriId"].asString()))
        val arbeidsgivere = packet["$BEHOV.arbeidsgivere"].toList()
        val søknadInternDokumentId =
            packet["$BEHOV.selvstendig.søknadMeldingsreferanseId"]
                .takeUnless { it.isMissingNode || it.isNull }
                ?.let { InternDokumentId(UUID.fromString(it.asString())) }

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer.verdi) {
            loggInfo("Mottatt behov for $BEHOV", "skjæringstidspunkt" to skjæringstidspunkt.toString())
            if (!støttes(arbeidsgivere, søknadInternDokumentId)) {
                loggError(
                    "Behov for $BEHOV med inntektskilder som ikke støttes ennå, svarer ikke",
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    "inntektskilder" to arbeidsgivere.map { it.path("inntektskilde").asString() }.toString(),
                )
                return@medMdc
            }
            try {
                val grunnlag =
                    if (søknadInternDokumentId != null) {
                        fastsettForSelvstendig(fødselsnummer, skjæringstidspunkt, rettighetskategoriId, søknadInternDokumentId)
                    } else {
                        val inntektsmeldingIder = arbeidsgivere.map { InternDokumentId(UUID.fromString(it.path("inntektsmeldingMeldingsreferanseId").asString())) }
                        fastsett(fødselsnummer, skjæringstidspunkt, rettighetskategoriId, inntektsmeldingIder)
                    }
                packet["@løsning"] = mapOf(BEHOV to mapOf("id" to grunnlag.id.uuid.toString()))
                loggInfo(
                    "Publiserer løsning på $BEHOV",
                    "sykepengegrunnlagId" to grunnlag.id.uuid.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                context.publish(packet.toJson())
            } catch (ex: Exception) {
                loggError(
                    "Feil under håndtering av behov $BEHOV",
                    ex,
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    "rettighetskategoriId" to rettighetskategoriId.uuid.toString(),
                )
            }
        }
    }

    private fun støttes(
        arbeidsgivere: List<JsonNode>,
        søknadInternDokumentId: InternDokumentId?,
    ) = if (søknadInternDokumentId != null) {
        arbeidsgivere.isEmpty()
    } else {
        arbeidsgivere.isNotEmpty() && arbeidsgivere.all { it.path("inntektskilde").asString() == Inntektskilde.INNTEKTSMELDING.name }
    }

    private fun fastsett(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
        rettighetskategoriId: RettighetskategoriId,
        inntektsmeldingIder: List<InternDokumentId>,
    ): Sykepengegrunnlag {
        val hovedkategori = rettighetskategoriClient.hentHovedkategori(rettighetskategoriId, fødselsnummer)
        check(hovedkategori == Hovedkategori.ARBEIDSTAKER) { "Hovedkategorien $hovedkategori støttes ikke ennå" }
        val inntektsmeldinger = inntektsmeldinger.hent(inntektsmeldingIder)
        return Sykepengegrunnlag
            .fastsett(fødselsnummer, skjæringstidspunkt, rettighetskategoriId, inntektsmeldinger, nå = Instant.now())
            .also { lagre(it, inntektsmeldinger) }
    }

    private fun fastsettForSelvstendig(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
        rettighetskategoriId: RettighetskategoriId,
        søknadInternDokumentId: InternDokumentId,
    ): Sykepengegrunnlag {
        val hovedkategori = rettighetskategoriClient.hentHovedkategori(rettighetskategoriId, fødselsnummer)
        check(hovedkategori == Hovedkategori.SELVSTENDIG_NAERINGSDRIVENDE) { "Hovedkategorien $hovedkategori passer ikke med søknaden fra selvstendig næringsdrivende" }
        val søknad = søknader.hent(søknadInternDokumentId)
        return Sykepengegrunnlag
            .fastsettForSelvstendig(fødselsnummer, skjæringstidspunkt, rettighetskategoriId, søknad, nå = Instant.now())
            .also { lagre(it, inntektsmeldinger = emptyList()) }
    }

    private fun lagre(
        grunnlag: Sykepengegrunnlag,
        inntektsmeldinger: List<Inntektsmelding>,
    ) {
        sessionOf(dataSource).use { session ->
            session.transaction { tx -> PgSykepengegrunnlagRepository(tx).lagre(grunnlag, inntektsmeldinger) }
        }
    }

    private companion object {
        const val BEHOV = "Sykepengegrunnlag"
    }
}
