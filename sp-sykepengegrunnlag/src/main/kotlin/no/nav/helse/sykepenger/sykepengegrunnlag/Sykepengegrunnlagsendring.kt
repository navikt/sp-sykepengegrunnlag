package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import io.micrometer.core.instrument.MeterRegistry
import kotliquery.sessionOf
import no.nav.sykepenger.libs.logging.loggInfo
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Endringer i grunnlaget etter vilkårsprøvingen går gjennom sp-sykepengegrunnlag (ADR 0020). Endringer for skjæringstidspunkter uten
 * grunnlag, for eksempel fra før skyggedriften, telles og ignoreres i stedet for å feile. Gir endringen ikke noe nytt grunnlag, for eksempel
 * fordi inntekten er den samme som før, lagres og sendes ingenting.
 */
internal class Sykepengegrunnlagsendring(
    private val dataSource: DataSource,
) {
    fun endre(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
        opphav: Opphav,
        context: MessageContext,
        meterRegistry: MeterRegistry,
        endring: (forrige: Sykepengegrunnlag) -> Sykepengegrunnlag?,
    ) {
        val (forrige, nytt) =
            sessionOf(dataSource).use { session ->
                session.transaction { tx ->
                    val repository = PgSykepengegrunnlagRepository(tx)
                    val forrige = repository.finnNyesteOrNull(fødselsnummer, skjæringstidspunkt)
                    forrige to
                        forrige?.let(endring)?.also {
                            repository.lagre(it, inntektsmeldinger = emptyList(), aOrdningsoppslag = null)
                        }
                }
            }
        if (forrige == null) {
            meterRegistry.counter(METRIKK_UTEN_GRUNNLAG, "opphav", opphav.iMeldinger).increment()
            loggInfo(
                "Ignorerer endring av sykepengegrunnlaget fordi det ikke finnes noe grunnlag for skjæringstidspunktet",
                "opphav" to opphav.iMeldinger,
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            return
        }
        if (nytt == null) {
            loggInfo(
                "Endringen gir ikke noe nytt sykepengegrunnlag",
                "opphav" to opphav.iMeldinger,
                "sykepengegrunnlagId" to forrige.id.uuid.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            return
        }
        nytt.hendelser.forEach { context.publish(it.fødselsnummer.verdi, it.tilMelding()) }
        loggInfo(
            "Lagret nytt sykepengegrunnlag fra endring og sendte endret_sykepengegrunnlag",
            "opphav" to nytt.opphav.iMeldinger,
            "sykepengegrunnlagId" to nytt.id.uuid.toString(),
            "skjæringstidspunkt" to skjæringstidspunkt.toString(),
        )
    }

    private fun EndretSykepengegrunnlag.tilMelding() =
        JsonMessage
            .newMessage(
                "endret_sykepengegrunnlag",
                mapOf(
                    "fødselsnummer" to fødselsnummer.verdi,
                    "skjæringstidspunkt" to skjæringstidspunkt,
                    "sykepengegrunnlagId" to sykepengegrunnlagId.uuid,
                    "forrigeSykepengegrunnlagId" to forrigeSykepengegrunnlagId.uuid,
                    "opphav" to opphav.iMeldinger,
                    "manuellVurdering" to manuellVurdering,
                ),
            ).toJson()

    private companion object {
        const val METRIKK_UTEN_GRUNNLAG = "sykepengegrunnlag_endring_uten_grunnlag"
    }
}
