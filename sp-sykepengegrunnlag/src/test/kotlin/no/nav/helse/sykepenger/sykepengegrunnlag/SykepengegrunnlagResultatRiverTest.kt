package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.sykepenger.libs.testing.assertions.assertJsonEquals
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID

internal class SykepengegrunnlagResultatRiverTest {
    private val spedisjon = FakeSpedisjon()
    private val rettighetskategori = FakeRettighetskategori()
    private val inntektskomponenten = FakeInntektskomponenten()
    private val rapid =
        TestRapid().apply {
            registrerRivers(TestDatabase.dataSource, spedisjon.client, rettighetskategori.client, inntektskomponenten.client)
        }

    @BeforeEach
    fun tømDatabasen() {
        TestDatabase.tøm()
    }

    @Test
    fun `svarer med grunnlaget fastsatt fra inntektsmelding`() {
        val id = fastsett(lagArbeidsgiver(A1, spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "30000")))

        assertJsonEquals(
            """
            {
              "opphav": "vilkårsprøving",
              "beregningsgrunnlag": 360000.00,
              "sykepengegrunnlag": 360000.00,
              "fastsatt": "EtterHovedregel",
              "grunnbeløp": 130160.00,
              "grunnbeløpVirkningstidspunkt": "2025-05-01",
              "seksG": 780960.00,
              "arbeidsgivere": [
                {
                  "orgnummer": "$A1",
                  "inntektskilde": "INNTEKTSMELDING",
                  "omregnetÅrsinntekt": 360000.00,
                  "skjønnsfastsatt": null,
                  "fastsattÅrsinntekt": 360000.00,
                  "deaktivert": false
                }
              ],
              "selvstendig": null
            }
            """,
            resultatFor(id),
        )
    }

    @Test
    fun `svarer med grunnlaget fastsatt fra a-ordningen`() {
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 10), "30000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 11), "30000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 12), "30000")
        val id = fastsett(lagAOrdningen(A1))

        val arbeidsgiver = resultatFor(id).path("arbeidsgivere").single()
        assertEquals("A_ORDNINGEN", arbeidsgiver.path("inntektskilde").asString())
        assertEquals(360000.0, arbeidsgiver.path("omregnetÅrsinntekt").asDouble())
        assertEquals(360000.0, arbeidsgiver.path("fastsattÅrsinntekt").asDouble())
    }

    @Test
    fun `svarer med grunnlaget for selvstendig næringsdrivende`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER, hovedkategori = "SELVSTENDIG_NAERINGSDRIVENDE")
        val søknad =
            spedisjon.lagSelvstendigSøknad(
                FØDSELSNUMMER,
                listOf(
                    FakeSpedisjon.InntektsÅr(2016, næringsinntekt = 500000),
                    FakeSpedisjon.InntektsÅr(2015, næringsinntekt = 450000),
                    FakeSpedisjon.InntektsÅr(2014, næringsinntekt = 380000),
                ),
            )
        rapid.sendTestMessage(
            lagSykepengegrunnlagBehov(rettighetskategoriId, emptyList(), skjæringstidspunkt = LocalDate.of(2018, 6, 16), søknadMeldingsreferanseId = søknad),
            FØDSELSNUMMER,
        )
        val id =
            sisteMelding()
                .path("@løsning")
                .path("Sykepengegrunnlag")
                .path("id")
                .asString()

        assertJsonEquals(
            """
            {
              "opphav": "vilkårsprøving",
              "beregningsgrunnlag": 478906.00,
              "sykepengegrunnlag": 478906.00,
              "fastsatt": "EtterHovedregel",
              "grunnbeløp": 96883.00,
              "grunnbeløpVirkningstidspunkt": "2018-05-01",
              "seksG": 581298.00,
              "arbeidsgivere": [],
              "selvstendig": {
                "beregningsgrunnlag": 478906.00,
                "fastsattÅrsinntekt": 478906.00,
                "pensjonsgivendeInntekter": [
                  {"år": 2016, "beløp": 500000.00},
                  {"år": 2015, "beløp": 450000.00},
                  {"år": 2014, "beløp": 380000.00}
                ]
              }
            }
            """,
            resultatFor(id),
        )
    }

    @Test
    fun `svarer med det skjønnsfastsatte grunnlaget`() {
        fastsett(lagArbeidsgiver(A1, spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "30000")))
        rapid.sendTestMessage(lagSkjønnsmessigFastsettelse(A1, årlig = 400000.0), FØDSELSNUMMER)
        val id = sisteMelding().path("sykepengegrunnlagId").asString()

        val resultat = resultatFor(id)

        assertEquals("skjønnsfastsettelse", resultat.path("opphav").asString())
        assertEquals("EtterSkjønn", resultat.path("fastsatt").asString())
        assertEquals(400000.0, resultat.path("beregningsgrunnlag").asDouble())
        val arbeidsgiver = resultat.path("arbeidsgivere").single()
        assertEquals(360000.0, arbeidsgiver.path("omregnetÅrsinntekt").asDouble())
        assertEquals(400000.0, arbeidsgiver.path("skjønnsfastsatt").asDouble())
        assertEquals(400000.0, arbeidsgiver.path("fastsattÅrsinntekt").asDouble())
    }

    @Test
    fun `svarer med grunnlaget der et arbeidsforhold er deaktivert`() {
        fastsett(
            lagArbeidsgiver(A1, spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "30000")),
            lagArbeidsgiver(A2, spedisjon.lagInntektsmelding(FØDSELSNUMMER, A2, beregnetInntekt = "10000")),
        )
        rapid.sendTestMessage(lagOverstyrArbeidsforhold(A2), FØDSELSNUMMER)
        val id = sisteMelding().path("sykepengegrunnlagId").asString()

        val resultat = resultatFor(id)

        assertEquals("overstyring_arbeidsforhold", resultat.path("opphav").asString())
        assertEquals(360000.0, resultat.path("beregningsgrunnlag").asDouble())
        val deaktivert = resultat.path("arbeidsgivere").single { it.path("orgnummer").asString() == A2 }
        assertEquals(true, deaktivert.path("deaktivert").asBoolean())
        assertEquals(120000.0, deaktivert.path("omregnetÅrsinntekt").asDouble())
        assertEquals(0.0, deaktivert.path("fastsattÅrsinntekt").asDouble())
    }

    @Test
    fun `svarer ikke når ID-en er ukjent`() {
        rapid.sendTestMessage(lagResultatbehov(UUID.randomUUID().toString()), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
    }

    private fun fastsett(vararg arbeidsgivere: String): String {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, arbeidsgivere.toList()), FØDSELSNUMMER)
        return sisteMelding()
            .path("@løsning")
            .path("Sykepengegrunnlag")
            .path("id")
            .asString()
    }

    private fun resultatFor(id: String): JsonNode {
        val antallFør = rapid.inspektør.size
        rapid.sendTestMessage(lagResultatbehov(id), FØDSELSNUMMER)
        assertEquals(antallFør + 1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(antallFør))
        return rapid.inspektør
            .message(antallFør)
            .path("@løsning")
            .path("SykepengegrunnlagResultat")
    }

    private fun sisteMelding() = rapid.inspektør.message(rapid.inspektør.size - 1)

    @Language("JSON")
    private fun lagResultatbehov(sykepengegrunnlagId: String) =
        """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@opprettet": "${LocalDateTime.now()}",
          "@behov": ["SykepengegrunnlagResultat"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "SykepengegrunnlagResultat": {
            "sykepengegrunnlagId": "$sykepengegrunnlagId"
          }
        }
        """

    @Language("JSON")
    private fun lagSykepengegrunnlagBehov(
        rettighetskategoriId: UUID,
        arbeidsgivere: List<String>,
        skjæringstidspunkt: LocalDate = SKJÆRINGSTIDSPUNKT,
        søknadMeldingsreferanseId: UUID? = null,
    ) = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@opprettet": "${LocalDateTime.now()}",
          "@behov": ["Sykepengegrunnlag"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "Sykepengegrunnlag": {
            "skjæringstidspunkt": "$skjæringstidspunkt",
            "rettighetskategoriId": "$rettighetskategoriId",
            "arbeidsgivere": [${arbeidsgivere.joinToString()}]${søknadMeldingsreferanseId?.let { ""","selvstendig": {"søknadMeldingsreferanseId": "$it"}""" } ?: ""}
          }
        }
        """

    @Language("JSON")
    private fun lagArbeidsgiver(
        orgnummer: String,
        inntektsmeldingMeldingsreferanseId: UUID,
    ) = """{"orgnummer": "$orgnummer", "inntektskilde": "INNTEKTSMELDING", "inntektsmeldingMeldingsreferanseId": "$inntektsmeldingMeldingsreferanseId"}"""

    @Language("JSON")
    private fun lagAOrdningen(orgnummer: String) = """{"orgnummer": "$orgnummer", "inntektskilde": "A_ORDNINGEN"}"""

    @Language("JSON")
    private fun lagSkjønnsmessigFastsettelse(
        orgnummer: String,
        årlig: Double,
    ) = """
        {
          "@event_name": "skjønnsmessig_fastsettelse",
          "@id": "${UUID.randomUUID()}",
          "@opprettet": "${LocalDateTime.now()}",
          "fødselsnummer": "$FØDSELSNUMMER",
          "aktørId": "1234567891011",
          "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
          "arbeidsgivere": [{"organisasjonsnummer": "$orgnummer", "årlig": $årlig, "fraÅrlig": 360000.0}],
          "saksbehandlerOid": "${UUID.randomUUID()}",
          "saksbehandlerNavn": "Saks Behandler",
          "saksbehandlerIdent": "X999999",
          "saksbehandlerEpost": "saks.behandler@nav.no"
        }
        """

    @Language("JSON")
    private fun lagOverstyrArbeidsforhold(orgnummer: String) =
        """
        {
          "@event_name": "overstyr_arbeidsforhold",
          "@id": "${UUID.randomUUID()}",
          "@opprettet": "${LocalDateTime.now()}",
          "fødselsnummer": "$FØDSELSNUMMER",
          "aktørId": "1234567891011",
          "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
          "overstyrteArbeidsforhold": [
            {"orgnummer": "$orgnummer", "deaktivert": true, "begrunnelse": "Ikke aktivt arbeidsforhold", "forklaring": "Sluttet før skjæringstidspunktet"}
          ],
          "saksbehandlerOid": "${UUID.randomUUID()}",
          "saksbehandlerNavn": "Saks Behandler",
          "saksbehandlerIdent": "X999999",
          "saksbehandlerEpost": "saks.behandler@nav.no"
        }
        """

    private companion object {
        val A1 = lagOrganisasjonsnummer()
        val A2 = lagOrganisasjonsnummer()
        val FØDSELSNUMMER = lagIdentitetsnummer()
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
