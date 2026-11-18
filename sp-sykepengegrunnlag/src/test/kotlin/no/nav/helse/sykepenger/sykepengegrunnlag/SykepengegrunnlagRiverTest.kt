package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.sykepenger.libs.testing.assertions.assertJsonEquals
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

internal class SykepengegrunnlagRiverTest {
    private val spedisjon = FakeSpedisjon()
    private val rettighetskategori = FakeRettighetskategori()
    private val rapid =
        TestRapid().apply {
            registrerRivers(TestDatabase.dataSource, spedisjon.client, rettighetskategori.client)
        }

    @BeforeEach
    fun tømDatabasen() {
        TestDatabase.tøm()
    }

    @Test
    fun `fastsetter grunnlaget fra inntektsmeldingen til én arbeidsgiver`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val mottatt = LocalDateTime.of(2026, 1, 15, 9, 30)
        val inntektsmeldingId = UUID.randomUUID()
        val inntektsmelding =
            spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "30000.0", mottattDato = mottatt, inntektsmeldingId = inntektsmeldingId)

        rapid.sendTestMessage(
            lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, inntektsmelding))),
            FØDSELSNUMMER,
        )

        assertEquals(1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(0))
        val løsning = rapid.inspektør.message(0).løsning()
        assertEquals(setOf("id"), løsning.propertyNames().toSet())
        val id = UUID.fromString(løsning.path("id").asString())

        val grunnlag = TestDatabase.grunnlag().single()
        assertEquals(id, grunnlag.id)
        assertEquals(FØDSELSNUMMER, grunnlag.fødselsnummer)
        assertEquals(SKJÆRINGSTIDSPUNKT, grunnlag.skjæringstidspunkt)
        assertEquals(rettighetskategoriId, grunnlag.rettighetskategoriId)
        assertEquals("VILKÅRSPRØVING", grunnlag.opphav)
        assertBeløp("360000", grunnlag.beregningsgrunnlag)
        assertBeløp("360000", grunnlag.sykepengegrunnlag)
        assertBeløp("130160", grunnlag.grunnbeløp)

        assertEquals(
            listOf(
                TestDatabase.Arbeidsgiverrad(
                    sykepengegrunnlagId = id,
                    orgnummer = A1,
                    inntektskilde = "INNTEKTSMELDING",
                    inntektsmeldingInternDokumentId = inntektsmelding,
                    omregnetÅrsinntekt = grunnlag.beregningsgrunnlag,
                ),
            ),
            TestDatabase.arbeidsgivere(),
        )

        val lagretInntektsmelding = TestDatabase.inntektsmeldinger().single()
        assertEquals(inntektsmelding, lagretInntektsmelding.internDokumentId)
        assertEquals(inntektsmeldingId, lagretInntektsmelding.inntektsmeldingId)
        assertEquals(A1, lagretInntektsmelding.orgnummer)
        assertBeløp("30000", lagretInntektsmelding.beregnetInntekt)
        assertEquals(mottatt.atZone(ZoneId.of("Europe/Oslo")).toInstant(), lagretInntektsmelding.mottattDato)
    }

    @Test
    fun `beregningsgrunnlaget er summen av omregnet årsinntekt hos alle arbeidsgiverne`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im1 = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "20000")
        val im2 = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A2, beregnetInntekt = "15000.5")

        rapid.sendTestMessage(
            lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im1), lagArbeidsgiver(A2, im2))),
            FØDSELSNUMMER,
        )

        val id =
            UUID.fromString(
                rapid.inspektør
                    .message(0)
                    .løsning()
                    .path("id")
                    .asString(),
            )
        val grunnlag = TestDatabase.grunnlag().single()
        assertEquals(id, grunnlag.id)
        assertBeløp("420006", grunnlag.beregningsgrunnlag)
        assertBeløp("420006", grunnlag.sykepengegrunnlag)

        val arbeidsgivere = TestDatabase.arbeidsgivere()
        assertEquals(listOf(A1 to im1, A2 to im2), arbeidsgivere.map { it.orgnummer to it.inntektsmeldingInternDokumentId })
        assertBeløp("240000", arbeidsgivere[0].omregnetÅrsinntekt)
        assertBeløp("180006", arbeidsgivere[1].omregnetÅrsinntekt)
        assertEquals(setOf(im1, im2), TestDatabase.inntektsmeldinger().map { it.internDokumentId }.toSet())

        val subsumsjon = TestDatabase.subsumsjoner().single()
        assertEquals(id, subsumsjon.sykepengegrunnlagId)
        assertEquals("8-10", subsumsjon.paragraf)
        assertEquals(2, subsumsjon.ledd)
        assertEquals(1, subsumsjon.punktum)
        assertEquals("VILKAR_BEREGNET", subsumsjon.utfall)
        assertJsonEquals(
            """{"maksimaltSykepengegrunnlag": 780960.0, "skjæringstidspunkt": "2026-01-01", "grunnlagForSykepengegrunnlag": 420006.0}""",
            subsumsjon.input,
        )
        assertJsonEquals("""{"erBegrenset": false}""", subsumsjon.output)
    }

    @Test
    fun `sykepengegrunnlaget begrenses til 6G`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "70000")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)

        val grunnlag = TestDatabase.grunnlag().single()
        assertBeløp("840000", grunnlag.beregningsgrunnlag)
        assertBeløp("780960", grunnlag.sykepengegrunnlag)
        val subsumsjon = TestDatabase.subsumsjoner().single()
        assertJsonEquals(
            """{"maksimaltSykepengegrunnlag": 780960.0, "skjæringstidspunkt": "2026-01-01", "grunnlagForSykepengegrunnlag": 840000.0}""",
            subsumsjon.input,
        )
        assertJsonEquals("""{"erBegrenset": true}""", subsumsjon.output)
    }

    @Test
    fun `beregningsgrunnlag på nøyaktig 6G er ikke begrenset og rundes ikke av`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "49929")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im)), skjæringstidspunkt = LocalDate.of(2019, 5, 1)), FØDSELSNUMMER)

        val grunnlag = TestDatabase.grunnlag().single()
        assertBeløp("599148", grunnlag.beregningsgrunnlag)
        assertBeløp("599148", grunnlag.sykepengegrunnlag)
        assertJsonEquals("""{"erBegrenset": false}""", TestDatabase.subsumsjoner().single().output)
    }

    @Test
    fun `sykepengegrunnlaget under 6G rundes ikke av`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "5535.5")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)

        assertBeløp("66426", TestDatabase.grunnlag().single().sykepengegrunnlag)
    }

    @ParameterizedTest(name = "skjæringstidspunkt {0} gir grunnbeløpet {1} og 6G {2}")
    @CsvSource(
        "2019-04-30, 96883, 581298",
        "2019-05-01, 99858, 599148",
        "2020-05-01, 101351, 608106",
        "2025-04-30, 124028, 744168",
        "2025-05-01, 130160, 780960",
        "2026-05-01, 136549, 819294",
    )
    fun `grunnbeløpet er det som gjelder på skjæringstidspunktet`(
        skjæringstidspunkt: LocalDate,
        grunnbeløp: String,
        seksG: String,
    ) {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "100000")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im)), skjæringstidspunkt = skjæringstidspunkt), FØDSELSNUMMER)

        val grunnlag = TestDatabase.grunnlag().single()
        assertBeløp(grunnbeløp, grunnlag.grunnbeløp)
        assertBeløp(seksG, grunnlag.sykepengegrunnlag)
    }

    @Test
    fun `svarer ikke når Spedisjon mangler en inntektsmelding`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)

        rapid.sendTestMessage(
            lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im), lagArbeidsgiver(A2, UUID.randomUUID()))),
            FØDSELSNUMMER,
        )

        assertSvarteIkke()
    }

    @Test
    fun `svarer ikke når Spedisjon feiler`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)
        spedisjon.feiler()

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)

        assertSvarteIkke()
    }

    @Test
    fun `svarer ikke når sp-rettighetskategori feiler`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)
        rettighetskategori.feiler()

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)

        assertSvarteIkke()
    }

    @Test
    fun `svarer ikke når sp-rettighetskategori ikke kjenner rettighetskategorien`() {
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(UUID.randomUUID(), listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)

        assertSvarteIkke()
    }

    @Test
    fun `svarer ikke når hovedkategorien ikke er arbeidstaker`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER, hovedkategori = "SELVSTENDIG_NAERINGSDRIVENDE")
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)

        assertSvarteIkke()
    }

    @Test
    fun `svarer ikke ennå når en arbeidsgiver har inntektskilden A_ORDNINGEN`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)

        val behov =
            lagSykepengegrunnlagBehov(
                rettighetskategoriId,
                listOf(lagArbeidsgiver(A1, im), lagArbeidsgiver(A2, inntektsmeldingMeldingsreferanseId = null, inntektskilde = "A_ORDNINGEN")),
            )
        rapid.sendTestMessage(behov, FØDSELSNUMMER)

        assertSvarteIkke()
    }

    @Test
    fun `behandler ikke behov som allerede er løst`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)
        val behov = objectMapper.readTree(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im)))) as ObjectNode
        behov.putObject("@løsning").putObject("Sykepengegrunnlag").put("id", UUID.randomUUID().toString())

        rapid.sendTestMessage(behov.toString(), FØDSELSNUMMER)

        assertSvarteIkke()
    }

    @Test
    fun `lagrer tidspunktet for hvert oppslag når samme inntektsmelding brukes på nytt`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)
        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im)), skjæringstidspunkt = SKJÆRINGSTIDSPUNKT.plusMonths(2)), FØDSELSNUMMER)

        assertEquals(1, TestDatabase.inntektsmeldinger().size)
        val oppslag = TestDatabase.inntektsmeldingoppslag()
        assertEquals(TestDatabase.grunnlag().map { it.id }, oppslag.map { it.first })
        assertTrue(oppslag[0].second < oppslag[1].second)
    }

    @Test
    fun `fastsetter grunnlaget for selvstendig næringsdrivende fra den pensjonsgivende inntekten i søknaden`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER, hovedkategori = "SELVSTENDIG_NAERINGSDRIVENDE")
        val søknadId = UUID.randomUUID()
        val inntektsÅr =
            listOf(
                FakeSpedisjon.InntektsÅr(2016, næringsinntekt = 400000, lønnsinntekt = 60000, lønnsinntektBarePensjonsdel = 40000),
                FakeSpedisjon.InntektsÅr(2015, næringsinntekt = 440000, næringsinntektFraFiskeFangstEllerFamiliebarnehage = 10000),
                FakeSpedisjon.InntektsÅr(2014, næringsinntekt = 380000, erFerdigLignet = false),
            )
        val søknad = spedisjon.lagSelvstendigSøknad(FØDSELSNUMMER, inntektsÅr, søknadId = søknadId)

        rapid.sendTestMessage(
            lagSykepengegrunnlagBehov(rettighetskategoriId, emptyList(), skjæringstidspunkt = LocalDate.of(2018, 6, 16), søknadMeldingsreferanseId = søknad),
            FØDSELSNUMMER,
        )

        val id =
            UUID.fromString(
                rapid.inspektør
                    .message(0)
                    .løsning()
                    .path("id")
                    .asString(),
            )
        val grunnlag = TestDatabase.grunnlag().single()
        assertEquals(id, grunnlag.id)
        assertEquals(LocalDate.of(2018, 6, 16), grunnlag.skjæringstidspunkt)
        assertBeløp("96883", grunnlag.grunnbeløp)
        assertBeløp("478906", grunnlag.beregningsgrunnlag)
        assertBeløp("478906", grunnlag.sykepengegrunnlag)
        assertEquals(emptyList<TestDatabase.Arbeidsgiverrad>(), TestDatabase.arbeidsgivere())

        val selvstendig = TestDatabase.selvstendiginntekter().single()
        assertEquals(id, selvstendig.sykepengegrunnlagId)
        assertEquals(søknad, selvstendig.søknadInternDokumentId)
        assertBeløp("478906", selvstendig.fastsattÅrsinntekt)

        assertEquals(listOf(TestDatabase.Søknadsrad(søknad, søknadId)), TestDatabase.søknader())
        assertEquals(
            listOf(
                TestDatabase.PensjonsgivendeInntektsrad(søknad, 2014, false, 0, 0, 380000, 0),
                TestDatabase.PensjonsgivendeInntektsrad(søknad, 2015, true, 0, 0, 440000, 10000),
                TestDatabase.PensjonsgivendeInntektsrad(søknad, 2016, true, 60000, 40000, 400000, 0),
            ),
            TestDatabase.pensjonsgivendeInntekter(),
        )

        val subsumsjon = TestDatabase.subsumsjoner().single { it.paragraf == "8-35" }
        assertEquals(id, subsumsjon.sykepengegrunnlagId)
        assertEquals(2, subsumsjon.ledd)
        assertEquals("VILKAR_BEREGNET", subsumsjon.utfall)
        assertJsonEquals(
            """
            {
              "pensjonsgivendeInntekter": [
                {"pensjonsgivendeInntekt": 500000, "årstall": 2016, "gjennomsnittligG": 91740},
                {"pensjonsgivendeInntekt": 450000, "årstall": 2015, "gjennomsnittligG": 89502},
                {"pensjonsgivendeInntekt": 380000, "årstall": 2014, "gjennomsnittligG": 87328}
              ],
              "nåværendeGrunnbeløp": 96883.0,
              "skjæringstidspunkt": "2018-06-16"
            }
            """,
            subsumsjon.input,
        )
        assertJsonEquals("""{"sykepengegrunnlag": 478906}""", subsumsjon.output)
        assertJsonEquals("""{"erBegrenset": false}""", TestDatabase.subsumsjoner().single { it.paragraf == "8-10" }.output)
    }

    @ParameterizedTest(name = "pensjonsgivende inntekter {0}, {1} og {2} gir fastsatt årsinntekt {3}, beregningsgrunnlag {4} og sykepengegrunnlag {5}")
    @CsvSource(
        "500000, 450000, 380000, 478906, 478906, 478906",
        "670000, 590000, 490000, 629943, 589138, 581298",
        "1500000, 0, 0, 528030, 258354, 258354",
    )
    fun `fastsetter grunnlaget for selvstendig næringsdrivende etter § 8-35 som Spleis`(
        inntekt2016: Int,
        inntekt2015: Int,
        inntekt2014: Int,
        fastsattÅrsinntekt: String,
        beregningsgrunnlag: String,
        sykepengegrunnlag: String,
    ) {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER, hovedkategori = "SELVSTENDIG_NAERINGSDRIVENDE")
        val inntektsÅr =
            listOf(
                FakeSpedisjon.InntektsÅr(2016, næringsinntekt = inntekt2016),
                FakeSpedisjon.InntektsÅr(2015, næringsinntekt = inntekt2015),
                FakeSpedisjon.InntektsÅr(2014, næringsinntekt = inntekt2014),
            )
        val søknad = spedisjon.lagSelvstendigSøknad(FØDSELSNUMMER, inntektsÅr)

        rapid.sendTestMessage(
            lagSykepengegrunnlagBehov(rettighetskategoriId, emptyList(), skjæringstidspunkt = LocalDate.of(2018, 5, 12), søknadMeldingsreferanseId = søknad),
            FØDSELSNUMMER,
        )

        val grunnlag = TestDatabase.grunnlag().single()
        assertBeløp(beregningsgrunnlag, grunnlag.beregningsgrunnlag)
        assertBeløp(sykepengegrunnlag, grunnlag.sykepengegrunnlag)
        assertBeløp(fastsattÅrsinntekt, TestDatabase.selvstendiginntekter().single().fastsattÅrsinntekt)
        assertJsonEquals("""{"sykepengegrunnlag": $sykepengegrunnlag}""", TestDatabase.subsumsjoner().single { it.paragraf == "8-35" }.output)
    }

    @Test
    fun `svarer ikke når Spedisjon mangler søknaden`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER, hovedkategori = "SELVSTENDIG_NAERINGSDRIVENDE")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, emptyList(), søknadMeldingsreferanseId = UUID.randomUUID()), FØDSELSNUMMER)

        assertSvarteIkke()
    }

    @Test
    fun `svarer ikke på søknad fra selvstendig næringsdrivende når hovedkategorien er arbeidstaker`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val søknad = spedisjon.lagSelvstendigSøknad(FØDSELSNUMMER, listOf(FakeSpedisjon.InntektsÅr(2025, næringsinntekt = 500000)))

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, emptyList(), søknadMeldingsreferanseId = søknad), FØDSELSNUMMER)

        assertSvarteIkke()
    }

    private fun assertSvarteIkke() {
        assertEquals(0, rapid.inspektør.size)
        assertEquals(emptyList<TestDatabase.Grunnlagsrad>(), TestDatabase.grunnlag())
        assertEquals(emptyList<TestDatabase.Inntektsmeldingrad>(), TestDatabase.inntektsmeldinger())
        assertEquals(emptyList<TestDatabase.Søknadsrad>(), TestDatabase.søknader())
    }

    private fun assertBeløp(
        forventet: String,
        faktisk: BigDecimal,
    ) = assertEquals(0, BigDecimal(forventet).compareTo(faktisk), "forventet $forventet, men var $faktisk")

    private fun JsonNode.løsning() = path("@løsning").path("Sykepengegrunnlag")

    @Language("JSON")
    private fun lagSykepengegrunnlagBehov(
        rettighetskategoriId: UUID,
        arbeidsgivere: List<String>,
        skjæringstidspunkt: LocalDate = SKJÆRINGSTIDSPUNKT,
        fødselsnummer: String = FØDSELSNUMMER,
        søknadMeldingsreferanseId: UUID? = null,
    ) = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@opprettet": "${LocalDateTime.now()}",
          "@behov": ["Sykepengegrunnlag"],
          "fødselsnummer": "$fødselsnummer",
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
        inntektsmeldingMeldingsreferanseId: UUID?,
        inntektskilde: String = "INNTEKTSMELDING",
    ) = """{"orgnummer": "$orgnummer", "inntektskilde": "$inntektskilde"${inntektsmeldingMeldingsreferanseId?.let { ", \"inntektsmeldingMeldingsreferanseId\": \"$it\"" } ?: ""}}"""

    private companion object {
        val A1 = lagOrganisasjonsnummer()
        val A2 = lagOrganisasjonsnummer()
        val FØDSELSNUMMER = lagIdentitetsnummer()
        val objectMapper = jacksonObjectMapper()
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
