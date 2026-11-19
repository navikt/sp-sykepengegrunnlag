package no.nav.helse.sykepenger.sykepengegrunnlag.opprydding_dev

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import kotliquery.TransactionalSession
import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import javax.sql.DataSource

internal class SlettPersonRiver(
    rapidsConnection: RapidsConnection,
    private val dataSource: DataSource,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition { it.requireValue("@event_name", "slett_person") }
                validate {
                    it.requireKey("@id", "fødselsnummer")
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val fødselsnummer = packet["fødselsnummer"].asString()

        sessionOf(dataSource).use { session ->
            session.transaction { tx -> slettPerson(tx, fødselsnummer) }
        }
        context.publish(fødselsnummer, lagPersonSlettet(fødselsnummer))
    }

    private fun slettPerson(
        tx: TransactionalSession,
        fødselsnummer: String,
    ) {
        listOf(
            SLETT_SUBSUMSJONER,
            SLETT_SKATTEOPPLYSNINGER,
            SLETT_ARBEIDSGIVERINNTEKTER,
            SLETT_SELVSTENDIGINNTEKTER,
            SLETT_SYKEPENGEGRUNNLAG,
            SLETT_INNTEKTSMELDINGER,
            SLETT_PENSJONSGIVENDE_INNTEKTER,
            SLETT_SØKNADER,
        ).forEach { sql ->
            tx.run(queryOf(sql, mapOf("fodselsnummer" to fødselsnummer)).asUpdate)
        }
    }

    @Language("JSON")
    private fun lagPersonSlettet(fødselsnummer: String): String =
        """
        {
            "@event_name": "person_slettet",
            "fødselsnummer": "$fødselsnummer"
        }
        """.trimIndent()

    private companion object {
        @Language("PostgreSQL")
        const val SLETT_SUBSUMSJONER =
            "DELETE FROM subsumsjon WHERE sykepengegrunnlag_id IN (SELECT id FROM sykepengegrunnlag WHERE fødselsnummer = :fodselsnummer)"

        @Language("PostgreSQL")
        const val SLETT_SKATTEOPPLYSNINGER =
            "DELETE FROM skatteopplysning WHERE sykepengegrunnlag_id IN (SELECT id FROM sykepengegrunnlag WHERE fødselsnummer = :fodselsnummer)"

        @Language("PostgreSQL")
        const val SLETT_ARBEIDSGIVERINNTEKTER =
            "DELETE FROM arbeidsgiverinntekt WHERE sykepengegrunnlag_id IN (SELECT id FROM sykepengegrunnlag WHERE fødselsnummer = :fodselsnummer)"

        @Language("PostgreSQL")
        const val SLETT_SELVSTENDIGINNTEKTER =
            "DELETE FROM selvstendiginntekt WHERE sykepengegrunnlag_id IN (SELECT id FROM sykepengegrunnlag WHERE fødselsnummer = :fodselsnummer)"

        @Language("PostgreSQL")
        const val SLETT_SYKEPENGEGRUNNLAG = "DELETE FROM sykepengegrunnlag WHERE fødselsnummer = :fodselsnummer"

        @Language("PostgreSQL")
        const val SLETT_INNTEKTSMELDINGER = "DELETE FROM inntektsmelding WHERE fødselsnummer = :fodselsnummer"

        @Language("PostgreSQL")
        const val SLETT_PENSJONSGIVENDE_INNTEKTER =
            "DELETE FROM pensjonsgivende_inntekt WHERE søknad_intern_dokument_id IN (SELECT søknad_intern_dokument_id FROM soknad WHERE fødselsnummer = :fodselsnummer)"

        @Language("PostgreSQL")
        const val SLETT_SØKNADER = "DELETE FROM soknad WHERE fødselsnummer = :fodselsnummer"
    }
}
