package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.sykepenger.libs.testing.assertions.assertJsonEquals
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

internal class SykepengegrunnlagRiverTest {
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

        assertBeløp("780960", grunnlag.seksG)
        assertFalse(grunnlag.er6GBegrenset)
        assertEquals(
            listOf(TestDatabase.Lovreferanserad(id, "folketrygdloven", "8-10", 2, 1, null, LocalDate.of(2020, 1, 1), "VILKAR_BEREGNET")),
            TestDatabase.lovreferanser(),
        )
    }

    @Test
    fun `sykepengegrunnlaget begrenses til 6G`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "70000")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)

        val grunnlag = TestDatabase.grunnlag().single()
        assertBeløp("840000", grunnlag.beregningsgrunnlag)
        assertBeløp("780960", grunnlag.sykepengegrunnlag)
        assertBeløp("780960", grunnlag.seksG)
        assertTrue(grunnlag.er6GBegrenset)
    }

    @Test
    fun `beregningsgrunnlag på nøyaktig 6G er ikke begrenset og rundes ikke av`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "49929")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im)), skjæringstidspunkt = LocalDate.of(2019, 5, 1)), FØDSELSNUMMER)

        val grunnlag = TestDatabase.grunnlag().single()
        assertBeløp("599148", grunnlag.beregningsgrunnlag)
        assertBeløp("599148", grunnlag.sykepengegrunnlag)
        assertFalse(grunnlag.er6GBegrenset)
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
    fun `fastsetter grunnlaget fra a-ordningen etter § 8-28`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 10), "30000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 11), "31000.50", fordel = "kontantytelse", beskrivelse = "fastloenn")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 12), "2000", inntektType = "YTELSE_FRA_OFFENTLIGE", beskrivelse = "sykepenger")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 12), "29000")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagAOrdningen(A1))), FØDSELSNUMMER)

        assertEquals(
            listOf(
                FakeInntektskomponenten.Kall(
                    fødselsnummer = FØDSELSNUMMER,
                    aktoerType = "NATURLIG_IDENT",
                    ainntektsfilter = "8-28Sykepenger",
                    formaal = "Sykepenger",
                    maanedFom = YearMonth.of(2025, 10),
                    maanedTom = YearMonth.of(2025, 12),
                    authorization = "Bearer token",
                ),
            ),
            inntektskomponenten.kall,
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
        assertBeløp("368002", grunnlag.beregningsgrunnlag)
        assertBeløp("368002", grunnlag.sykepengegrunnlag)

        val arbeidsgiver = TestDatabase.arbeidsgivere().single()
        assertEquals("A_ORDNINGEN", arbeidsgiver.inntektskilde)
        assertEquals(null, arbeidsgiver.inntektsmeldingInternDokumentId)
        assertBeløp("368002", arbeidsgiver.omregnetÅrsinntekt)
        assertTrue(TestDatabase.aOrdningsoppslag().single().let { it.first == id && it.second == A1 })
        assertEquals(
            listOf(
                TestDatabase.Skatteopplysningsrad(id, A1, YearMonth.of(2025, 10), BigDecimal("30000.00"), "LØNNSINNTEKT", "kontantytelse", "fastloenn"),
                TestDatabase.Skatteopplysningsrad(id, A1, YearMonth.of(2025, 11), BigDecimal("31000.50"), "LØNNSINNTEKT", "kontantytelse", "fastloenn"),
                TestDatabase.Skatteopplysningsrad(id, A1, YearMonth.of(2025, 12), BigDecimal("2000.00"), "YTELSE_FRA_OFFENTLIGE", "kontantytelse", "sykepenger"),
                TestDatabase.Skatteopplysningsrad(id, A1, YearMonth.of(2025, 12), BigDecimal("29000.00"), "LØNNSINNTEKT", "kontantytelse", "fastloenn"),
            ),
            TestDatabase.skatteopplysninger(),
        )
        assertEquals(emptyList<TestDatabase.Inntektsmeldingrad>(), TestDatabase.inntektsmeldinger())
    }

    @Test
    fun `fastsetter grunnlaget med inntektsmelding og ghost-arbeidsforhold fra a-ordningen`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "30000")
        listOf(10, 11, 12).forEach { måned ->
            inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, måned), "99999")
            inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A2, YearMonth.of(2025, måned), "10000")
            inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A3, YearMonth.of(2025, måned), "50000")
        }

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im), lagAOrdningen(A2))), FØDSELSNUMMER)

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
        assertBeløp("480000", grunnlag.beregningsgrunnlag)
        assertBeløp("480000", grunnlag.sykepengegrunnlag)
        val arbeidsgivere = TestDatabase.arbeidsgivere()
        assertEquals(listOf(A1 to "INNTEKTSMELDING", A2 to "A_ORDNINGEN"), arbeidsgivere.map { it.orgnummer to it.inntektskilde })
        assertBeløp("360000", arbeidsgivere[0].omregnetÅrsinntekt)
        assertBeløp("120000", arbeidsgivere[1].omregnetÅrsinntekt)
        assertEquals(listOf(id to A2), TestDatabase.aOrdningsoppslag())
        assertEquals(setOf(A2), TestDatabase.skatteopplysninger().map { it.orgnummer }.toSet())
        assertEquals(3, TestDatabase.skatteopplysninger().size)
        assertEquals(listOf(im), TestDatabase.inntektsmeldinger().map { it.internDokumentId })
        assertEquals(1, inntektskomponenten.kall.size)
    }

    @Test
    fun `lagrer hele svaret fra a-ordningen, og grunnlaget peker på oppslaget`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        listOf(10, 11, 12).forEach { måned ->
            inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, måned), "30000")
            inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A2, YearMonth.of(2025, måned), "10000")
        }

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagAOrdningen(A1))), FØDSELSNUMMER)

        val oppslag = TestDatabase.aOrdningsoppslagrader().single()
        assertEquals(FØDSELSNUMMER, oppslag.fødselsnummer)
        assertEquals(YearMonth.of(2025, 10), oppslag.førsteMåned)
        assertEquals(YearMonth.of(2025, 12), oppslag.sisteMåned)
        assertEquals("8-28Sykepenger", oppslag.ainntektsfilter)
        val virksomheterISvaret =
            objectMapper
                .readTree(oppslag.svar)
                .path("arbeidsInntektMaaned")
                .flatMap { it.path("arbeidsInntektInformasjon").path("inntektListe") }
                .map { it.path("virksomhet").path("identifikator").asString() }
        assertEquals(setOf(A1, A2), virksomheterISvaret.toSet())
        assertEquals(6, virksomheterISvaret.size)

        assertEquals(listOf(oppslag.id), TestDatabase.arbeidsgivere().map { it.aOrdningsoppslagId })
        assertEquals(setOf(A1), TestDatabase.skatteopplysninger().map { it.orgnummer }.toSet())
    }

    @Test
    fun `henter a-ordningen én gang når flere arbeidsgivere har inntektskilden A_ORDNINGEN, og begrenser grunnlaget til 6G`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        listOf(10, 11, 12).forEach { måned ->
            inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, måned), "40000")
            inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A2, YearMonth.of(2025, måned), "35000")
        }

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagAOrdningen(A1), lagAOrdningen(A2))), FØDSELSNUMMER)

        assertEquals(1, inntektskomponenten.kall.size)
        val grunnlag = TestDatabase.grunnlag().single()
        assertBeløp("900000", grunnlag.beregningsgrunnlag)
        assertBeløp("780960", grunnlag.sykepengegrunnlag)
        assertEquals(listOf(480000, 420000), TestDatabase.arbeidsgivere().map { it.omregnetÅrsinntekt.intValueExact() })
    }

    @Test
    fun `inntekten er 0 når a-ordningen ikke har noen inntekt for arbeidsgiveren`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "30000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 12), "30000")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im), lagAOrdningen(A2))), FØDSELSNUMMER)

        val id =
            UUID.fromString(
                rapid.inspektør
                    .message(0)
                    .løsning()
                    .path("id")
                    .asString(),
            )
        val ghost = TestDatabase.arbeidsgivere().single { it.orgnummer == A2 }
        assertEquals("A_ORDNINGEN", ghost.inntektskilde)
        assertBeløp("0", ghost.omregnetÅrsinntekt)
        assertBeløp("360000", TestDatabase.grunnlag().single().beregningsgrunnlag)
        assertEquals(listOf(id to A2), TestDatabase.aOrdningsoppslag())
        assertEquals(emptyList<TestDatabase.Skatteopplysningsrad>(), TestDatabase.skatteopplysninger())
    }

    @Test
    fun `deler alltid på tre måneder, også når a-ordningen bare har inntekt for noen av dem`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 12), "30000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A2, YearMonth.of(2025, 11), "10000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A2, YearMonth.of(2025, 12), "10000.01")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagAOrdningen(A1), lagAOrdningen(A2))), FØDSELSNUMMER)

        val arbeidsgivere = TestDatabase.arbeidsgivere()
        assertBeløp("120000", arbeidsgivere[0].omregnetÅrsinntekt)
        assertBeløp("80000.04", arbeidsgivere[1].omregnetÅrsinntekt)
    }

    @Test
    fun `inntekten fra a-ordningen blir ikke negativ`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 11), "1000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A1, YearMonth.of(2025, 12), "-5000")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagAOrdningen(A1))), FØDSELSNUMMER)

        assertBeløp("0", TestDatabase.arbeidsgivere().single().omregnetÅrsinntekt)
        assertEquals(2, TestDatabase.skatteopplysninger().size)
    }

    @Test
    fun `lagrer lovreferansen til § 8-28 tredje avsnitt bokstav a når en arbeidsgiver har inntekt fra a-ordningen`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1, beregnetInntekt = "30000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A2, YearMonth.of(2025, 11), "10000")
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, A2, YearMonth.of(2025, 12), "12500", inntektType = "YTELSE_FRA_OFFENTLIGE", beskrivelse = "sykepenger")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im), lagAOrdningen(A2))), FØDSELSNUMMER)

        val id =
            UUID.fromString(
                rapid.inspektør
                    .message(0)
                    .løsning()
                    .path("id")
                    .asString(),
            )
        assertEquals(
            listOf(
                TestDatabase.Lovreferanserad(id, "folketrygdloven", "8-28", 3, null, "a", LocalDate.of(2019, 1, 1), "VILKAR_BEREGNET"),
                TestDatabase.Lovreferanserad(id, "folketrygdloven", "8-10", 2, 1, null, LocalDate.of(2020, 1, 1), "VILKAR_BEREGNET"),
            ),
            TestDatabase.lovreferanser(),
        )
    }

    @Test
    fun `kaller ikke inntektskomponenten når ingen arbeidsgiver har inntektskilden A_ORDNINGEN`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)
        inntektskomponenten.feiler()

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im))), FØDSELSNUMMER)

        assertEquals(1, rapid.inspektør.size)
        assertEquals(emptyList<FakeInntektskomponenten.Kall>(), inntektskomponenten.kall)
    }

    @Test
    fun `svarer ikke når inntektskomponenten feiler`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)
        inntektskomponenten.feiler()

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im), lagAOrdningen(A2))), FØDSELSNUMMER)

        assertEquals(1, inntektskomponenten.kall.size)
        assertSvarteIkke()
        assertEquals(emptyList<TestDatabase.Skatteopplysningsrad>(), TestDatabase.skatteopplysninger())
    }

    @Test
    fun `svarer ikke når inntektskomponenten svarer med aktør-ID`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        inntektskomponenten.leggTilInntekt(FØDSELSNUMMER, "1234567890123", YearMonth.of(2025, 12), "30000", virksomhetstype = "AKTOER_ID")

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagAOrdningen(A1))), FØDSELSNUMMER)

        assertSvarteIkke()
    }

    @Test
    fun `svarer ikke når en arbeidsgiver har en ukjent inntektskilde`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)

        rapid.sendTestMessage(
            lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im), lagArbeidsgiver(A2, null, inntektskilde = "INFOTRYGD"))),
            FØDSELSNUMMER,
        )

        assertSvarteIkke()
        assertEquals(emptyList<FakeInntektskomponenten.Kall>(), inntektskomponenten.kall)
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
    fun `svarer med det samme grunnlaget når det samme behovet kommer på nytt`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im1 = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)
        val im2 = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A2)

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im1), lagArbeidsgiver(A2, im2), lagAOrdningen(A3))), FØDSELSNUMMER)
        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagAOrdningen(A3), lagArbeidsgiver(A2, im2), lagArbeidsgiver(A1, im1))), FØDSELSNUMMER)

        assertEquals(2, rapid.inspektør.size)
        val id = rapid.inspektør.message(0).løsning().path("id").asString()
        assertEquals(id, rapid.inspektør.message(1).løsning().path("id").asString())
        assertEquals(listOf(UUID.fromString(id)), TestDatabase.grunnlag().map { it.id })
        assertEquals(1, spedisjon.antallOppslag)
        assertEquals(1, rettighetskategori.antallOppslag)
        assertEquals(1, inntektskomponenten.kall.size)
    }

    @Test
    fun `svarer med det samme grunnlaget når det samme behovet fra selvstendig næringsdrivende kommer på nytt`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER, hovedkategori = "SELVSTENDIG_NAERINGSDRIVENDE")
        val søknad = spedisjon.lagSelvstendigSøknad(FØDSELSNUMMER, listOf(FakeSpedisjon.InntektsÅr(2025, næringsinntekt = 500000)))

        repeat(2) {
            rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, emptyList(), søknadMeldingsreferanseId = søknad), FØDSELSNUMMER)
        }

        val id = rapid.inspektør.message(0).løsning().path("id").asString()
        assertEquals(id, rapid.inspektør.message(1).løsning().path("id").asString())
        assertEquals(listOf(UUID.fromString(id)), TestDatabase.grunnlag().map { it.id })
        assertEquals(1, spedisjon.antallOppslag)
        assertEquals(1, rettighetskategori.antallOppslag)
    }

    @ParameterizedTest
    @EnumSource(EndringIBehovet::class)
    fun `fastsetter et nytt grunnlag når ett felt i behovet er ulikt`(endring: EndringIBehovet) {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im1 = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)
        val arbeidsgivere = listOf(lagArbeidsgiver(A1, im1), lagAOrdningen(A2))

        rapid.sendTestMessage(lagSykepengegrunnlagBehov(rettighetskategoriId, arbeidsgivere), FØDSELSNUMMER)
        val endretBehov =
            when (endring) {
                EndringIBehovet.SKJÆRINGSTIDSPUNKT -> lagSykepengegrunnlagBehov(rettighetskategoriId, arbeidsgivere, skjæringstidspunkt = SKJÆRINGSTIDSPUNKT.plusDays(1))
                EndringIBehovet.RETTIGHETSKATEGORI ->
                    lagSykepengegrunnlagBehov(rettighetskategori.lagRettighetskategori(FØDSELSNUMMER), arbeidsgivere)
                EndringIBehovet.ORGNUMMER -> lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im1), lagAOrdningen(A3)))
                EndringIBehovet.INNTEKTSKILDE ->
                    lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im1), lagArbeidsgiver(A2, spedisjon.lagInntektsmelding(FØDSELSNUMMER, A2))))
                EndringIBehovet.INNTEKTSMELDING ->
                    lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)), lagAOrdningen(A2)))
                EndringIBehovet.EN_ARBEIDSGIVER_MER -> lagSykepengegrunnlagBehov(rettighetskategoriId, arbeidsgivere + lagAOrdningen(A3))
                EndringIBehovet.EN_ARBEIDSGIVER_MINDRE -> lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im1)))
            }
        rapid.sendTestMessage(endretBehov, FØDSELSNUMMER)

        assertEquals(2, rapid.inspektør.size)
        val første = UUID.fromString(rapid.inspektør.message(0).løsning().path("id").asString())
        val andre = UUID.fromString(rapid.inspektør.message(1).løsning().path("id").asString())
        assertNotEquals(første, andre)
        assertEquals(setOf(første, andre), TestDatabase.grunnlag().map { it.id }.toSet())
        assertEquals(2, rettighetskategori.antallOppslag)
    }

    @Test
    fun `fastsetter et nytt grunnlag når Spleis vilkårsprøver på nytt etter en skjønnsfastsettelse`() {
        val rettighetskategoriId = rettighetskategori.lagRettighetskategori(FØDSELSNUMMER)
        val im = spedisjon.lagInntektsmelding(FØDSELSNUMMER, A1)
        val behov = lagSykepengegrunnlagBehov(rettighetskategoriId, listOf(lagArbeidsgiver(A1, im)))

        rapid.sendTestMessage(behov, FØDSELSNUMMER)
        rapid.sendTestMessage(lagSkjønnsmessigFastsettelse(A1, årlig = 400000.0), FØDSELSNUMMER)
        rapid.sendTestMessage(behov, FØDSELSNUMMER)

        assertEquals(3, rapid.inspektør.size)
        val vilkårsprøving = UUID.fromString(rapid.inspektør.message(0).løsning().path("id").asString())
        val skjønnsfastsatt = UUID.fromString(rapid.inspektør.message(1).path("sykepengegrunnlagId").asString())
        val nytt = UUID.fromString(rapid.inspektør.message(2).løsning().path("id").asString())
        assertEquals(3, setOf(vilkårsprøving, skjønnsfastsatt, nytt).size)
        assertEquals(setOf(vilkårsprøving, skjønnsfastsatt, nytt), TestDatabase.grunnlag().map { it.id }.toSet())
        assertEquals("VILKÅRSPRØVING", TestDatabase.grunnlag().single { it.id == nytt }.opphav)
    }

    enum class EndringIBehovet {
        SKJÆRINGSTIDSPUNKT,
        RETTIGHETSKATEGORI,
        ORGNUMMER,
        INNTEKTSKILDE,
        INNTEKTSMELDING,
        EN_ARBEIDSGIVER_MER,
        EN_ARBEIDSGIVER_MINDRE,
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

        assertJsonEquals(
            """
            [
              {"inntektsår": 2016, "beløp": 500000, "gjennomsnittligG": 91740},
              {"inntektsår": 2015, "beløp": 450000, "gjennomsnittligG": 89502},
              {"inntektsår": 2014, "beløp": 380000, "gjennomsnittligG": 87328}
            ]
            """,
            selvstendig.pensjonsgivendeInntekter,
        )
        assertFalse(grunnlag.er6GBegrenset)
        assertEquals(
            listOf(
                TestDatabase.Lovreferanserad(id, "folketrygdloven", "8-10", 2, 1, null, LocalDate.of(2020, 1, 1), "VILKAR_BEREGNET"),
                TestDatabase.Lovreferanserad(id, "folketrygdloven", "8-35", 2, null, null, LocalDate.of(2018, 12, 20), "VILKAR_BEREGNET"),
            ),
            TestDatabase.lovreferanser(),
        )
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

    private fun lagAOrdningen(orgnummer: String) = lagArbeidsgiver(orgnummer, inntektsmeldingMeldingsreferanseId = null, inntektskilde = "A_ORDNINGEN")

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

    private companion object {
        val A1 = lagOrganisasjonsnummer()
        val A2 = lagOrganisasjonsnummer()
        val A3 = lagOrganisasjonsnummer()
        val FØDSELSNUMMER = lagIdentitetsnummer()
        val objectMapper = jacksonObjectMapper()
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
