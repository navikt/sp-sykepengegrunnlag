package no.nav.helse.sykepenger.sykepengegrunnlag.opprydding_dev

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import kotliquery.queryOf
import kotliquery.sessionOf
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals

internal class SlettPersonRiverTest {
    private val rapid =
        TestRapid().apply {
            SlettPersonRiver(this, Database.dataSource)
        }

    @BeforeEach
    fun beforeEach() {
        Database.reset()
        rapid.reset()
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun shutdown() {
            Database.shutdown()
        }
    }

    @Test
    fun `publiserer person_slettet etter sletting`() {
        val fødselsnummer = lagIdentitetsnummer()

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(1, rapid.inspektør.size)
        val melding = rapid.inspektør.message(0)
        assertEquals("person_slettet", melding["@event_name"].asString())
        assertEquals(fødselsnummer, melding["fødselsnummer"].asString())
        assertEquals(fødselsnummer, rapid.inspektør.key(0))
    }

    @Test
    fun `sletter grunnlagene, inntektene, subsumsjonene, inntektsmeldingene og søknadene til personen`() {
        val fødselsnummer = lagIdentitetsnummer()
        val annenPerson = lagIdentitetsnummer()
        lagreGrunnlag(fødselsnummer)
        lagreSelvstendigGrunnlag(fødselsnummer)
        lagreGrunnlag(annenPerson)
        lagreSelvstendigGrunnlag(annenPerson)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(
            mapOf(
                "sykepengegrunnlag" to 0,
                "arbeidsgiverinntekt" to 0,
                "selvstendiginntekt" to 0,
                "subsumsjon" to 0,
                "inntektsmelding" to 0,
                "soknad" to 0,
                "pensjonsgivende_inntekt" to 0,
            ),
            antallRader(fødselsnummer),
        )
        assertEquals(
            mapOf(
                "sykepengegrunnlag" to 2,
                "arbeidsgiverinntekt" to 1,
                "selvstendiginntekt" to 1,
                "subsumsjon" to 1,
                "inntektsmelding" to 1,
                "soknad" to 1,
                "pensjonsgivende_inntekt" to 1,
            ),
            antallRader(annenPerson),
        )
    }

    private fun lagreSelvstendigGrunnlag(fødselsnummer: String) {
        @Language("PostgreSQL")
        val sql =
            """
            WITH soknad AS (
                INSERT INTO soknad (søknad_intern_dokument_id, søknad_id, fødselsnummer)
                VALUES (gen_random_uuid(), gen_random_uuid(), :fodselsnummer)
                RETURNING søknad_intern_dokument_id
            ), inntekt AS (
                INSERT INTO pensjonsgivende_inntekt (søknad_intern_dokument_id, år, er_ferdig_lignet, lønnsinntekt, lønnsinntekt_bare_pensjonsdel,
                                                     næringsinntekt, næringsinntekt_fra_fiske_fangst_eller_familiebarnehage)
                SELECT søknad_intern_dokument_id, 2025, true, 0, 0, 500000, 0 FROM soknad
            ), grunnlag AS (
                INSERT INTO sykepengegrunnlag (id, fødselsnummer, skjæringstidspunkt, rettighetskategori_id, opphav, beregningsgrunnlag,
                                               sykepengegrunnlag, grunnbeløp)
                VALUES (gen_random_uuid(), :fodselsnummer, '2026-01-01', gen_random_uuid(), 'VILKÅRSPRØVING', 500000, 500000, 130160)
                RETURNING id
            )
            INSERT INTO selvstendiginntekt (sykepengegrunnlag_id, søknad_intern_dokument_id, søknad_hentet, fastsatt_årsinntekt)
            SELECT grunnlag.id, soknad.søknad_intern_dokument_id, now(), 500000 FROM grunnlag, soknad
            """
        sessionOf(Database.dataSource).use {
            it.run(queryOf(sql, mapOf("fodselsnummer" to fødselsnummer)).asUpdate)
        }
    }

    private fun lagreGrunnlag(fødselsnummer: String) {
        @Language("PostgreSQL")
        val sql =
            """
            WITH inntektsmelding AS (
                INSERT INTO inntektsmelding (intern_dokument_id, inntektsmelding_id, fødselsnummer, orgnummer, beregnet_inntekt, mottatt_dato)
                VALUES (gen_random_uuid(), gen_random_uuid(), :fodselsnummer, '987654321', 30000, now())
                RETURNING intern_dokument_id
            ), grunnlag AS (
                INSERT INTO sykepengegrunnlag (id, fødselsnummer, skjæringstidspunkt, rettighetskategori_id, opphav, beregningsgrunnlag,
                                               sykepengegrunnlag, grunnbeløp)
                VALUES (gen_random_uuid(), :fodselsnummer, '2026-01-01', gen_random_uuid(), 'VILKÅRSPRØVING', 360000, 360000, 130160)
                RETURNING id
            ), arbeidsgiver AS (
                INSERT INTO arbeidsgiverinntekt (sykepengegrunnlag_id, orgnummer, inntektskilde, inntektsmelding_intern_dokument_id, inntektsmelding_hentet, omregnet_årsinntekt)
                SELECT grunnlag.id, '987654321', 'INNTEKTSMELDING', inntektsmelding.intern_dokument_id, now(), 360000 FROM grunnlag, inntektsmelding
            )
            INSERT INTO subsumsjon (sykepengegrunnlag_id, lovverk, versjon, paragraf, ledd, punktum, utfall, input, output)
            SELECT id, 'folketrygdloven', '2020-01-01', '8-10', 2, 1, 'VILKAR_BEREGNET', '{}', '{}' FROM grunnlag
            """
        sessionOf(Database.dataSource).use {
            it.run(queryOf(sql, mapOf("fodselsnummer" to fødselsnummer)).asUpdate)
        }
    }

    private fun antallRader(fødselsnummer: String): Map<String, Int> =
        mapOf(
            "sykepengegrunnlag" to antall("SELECT COUNT(*) FROM sykepengegrunnlag WHERE fødselsnummer = :fodselsnummer", fødselsnummer),
            "arbeidsgiverinntekt" to
                antall(
                    "SELECT COUNT(*) FROM arbeidsgiverinntekt a JOIN sykepengegrunnlag s ON s.id = a.sykepengegrunnlag_id WHERE s.fødselsnummer = :fodselsnummer",
                    fødselsnummer,
                ),
            "selvstendiginntekt" to
                antall(
                    "SELECT COUNT(*) FROM selvstendiginntekt i JOIN sykepengegrunnlag s ON s.id = i.sykepengegrunnlag_id WHERE s.fødselsnummer = :fodselsnummer",
                    fødselsnummer,
                ),
            "subsumsjon" to
                antall(
                    "SELECT COUNT(*) FROM subsumsjon u JOIN sykepengegrunnlag s ON s.id = u.sykepengegrunnlag_id WHERE s.fødselsnummer = :fodselsnummer",
                    fødselsnummer,
                ),
            "inntektsmelding" to antall("SELECT COUNT(*) FROM inntektsmelding WHERE fødselsnummer = :fodselsnummer", fødselsnummer),
            "soknad" to antall("SELECT COUNT(*) FROM soknad WHERE fødselsnummer = :fodselsnummer", fødselsnummer),
            "pensjonsgivende_inntekt" to
                antall(
                    "SELECT COUNT(*) FROM pensjonsgivende_inntekt p JOIN soknad s ON s.søknad_intern_dokument_id = p.søknad_intern_dokument_id WHERE s.fødselsnummer = :fodselsnummer",
                    fødselsnummer,
                ),
        )

    private fun antall(
        @Language("PostgreSQL") sql: String,
        fødselsnummer: String,
    ): Int =
        sessionOf(Database.dataSource).use { session ->
            session.run(queryOf(sql, mapOf("fodselsnummer" to fødselsnummer)).map { row -> row.int(1) }.asSingle)!!
        }

    private fun slettPersonMelding(fødselsnummer: String) =
        """
        {
            "@event_name": "slett_person",
            "@id": "${UUID.randomUUID()}",
            "fødselsnummer": "$fødselsnummer"
        }
        """.trimIndent()
}
