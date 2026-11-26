package no.nav.helse.sykepenger.sykepengegrunnlag

import no.nav.helse.sykepenger.sykepengegrunnlag.SykepengegrunnlagResultat.Fastsatt
import no.nav.helse.sykepenger.sykepengegrunnlag.SykepengegrunnlagResultat.Resultatkilde
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

internal class SykepengegrunnlagResultatTest {
    @Test
    fun `grunnlag fastsatt fra inntektsmelding`() {
        val resultat = SykepengegrunnlagResultat.fra(lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "30000")))

        assertEquals(
            SykepengegrunnlagResultat(
                opphav = Opphav.VILKÅRSPRØVING,
                beregningsgrunnlag = BigDecimal("360000.00"),
                sykepengegrunnlag = BigDecimal("360000.00"),
                fastsatt = Fastsatt.EtterHovedregel,
                grunnbeløp = BigDecimal("130160.00"),
                grunnbeløpVirkningstidspunkt = LocalDate.of(2025, 5, 1),
                seksG = BigDecimal("780960.00"),
                arbeidsgivere =
                    listOf(
                        SykepengegrunnlagResultat.Arbeidsgiver(
                            orgnummer = A1,
                            inntektskilde = Resultatkilde.INNTEKTSMELDING,
                            omregnetÅrsinntekt = BigDecimal("360000.00"),
                            skjønnsfastsatt = null,
                            fastsattÅrsinntekt = BigDecimal("360000.00"),
                            deaktivert = false,
                        ),
                    ),
                selvstendig = null,
            ),
            resultat,
        )
    }

    @Test
    fun `sykepengegrunnlaget er begrenset til 6G`() {
        val resultat = SykepengegrunnlagResultat.fra(lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "70000")))

        assertEquals(BigDecimal("840000.00"), resultat.beregningsgrunnlag)
        assertEquals(BigDecimal("780960.00"), resultat.seksG)
        assertEquals(resultat.seksG, resultat.sykepengegrunnlag)
    }

    @Test
    fun `skjønnsfastsatt grunnlag er fastsatt etter skjønn, og fastsatt årsinntekt er det skjønnsfastsatte beløpet`() {
        val resultat =
            SykepengegrunnlagResultat.fra(
                lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "30000"))
                    .skjønnsfastsett(SkjønnsfastsettelseId(UUID.randomUUID()), listOf(Skjønnsfastsettelse(A1, BigDecimal("400000")))),
            )

        assertEquals(Opphav.SKJØNNSFASTSETTELSE, resultat.opphav)
        assertEquals(Fastsatt.EtterSkjønn, resultat.fastsatt)
        val arbeidsgiver = resultat.arbeidsgivere.single()
        assertEquals(Resultatkilde.INNTEKTSMELDING, arbeidsgiver.inntektskilde)
        assertEquals(BigDecimal("360000.00"), arbeidsgiver.omregnetÅrsinntekt)
        assertEquals(BigDecimal("400000.00"), arbeidsgiver.skjønnsfastsatt)
        assertEquals(BigDecimal("400000.00"), arbeidsgiver.fastsattÅrsinntekt)
        assertEquals(BigDecimal("400000.00"), resultat.beregningsgrunnlag)
    }

    @Test
    fun `korrigert inntekt har kilden saksbehandler, og omregnet og fastsatt årsinntekt er den korrigerte inntekten`() {
        val resultat =
            SykepengegrunnlagResultat.fra(
                checkNotNull(
                    lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "30000"))
                        .overstyrInntekt(OverstyringId(UUID.randomUUID()), listOf(Inntektsoverstyring(A1, BigDecimal("372000")))),
                ),
            )

        assertEquals(Opphav.OVERSTYRING_INNTEKT, resultat.opphav)
        assertEquals(Fastsatt.EtterHovedregel, resultat.fastsatt)
        val arbeidsgiver = resultat.arbeidsgivere.single()
        assertEquals(Resultatkilde.SAKSBEHANDLER, arbeidsgiver.inntektskilde)
        assertEquals(BigDecimal("372000.00"), arbeidsgiver.omregnetÅrsinntekt)
        assertEquals(BigDecimal("372000.00"), arbeidsgiver.fastsattÅrsinntekt)
    }

    @Test
    fun `skjønnsfastsettelse etter korrigert inntekt gir det skjønnsfastsatte beløpet som fastsatt årsinntekt`() {
        val korrigert =
            checkNotNull(
                lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "30000"))
                    .overstyrInntekt(OverstyringId(UUID.randomUUID()), listOf(Inntektsoverstyring(A1, BigDecimal("372000")))),
            )
        val resultat =
            SykepengegrunnlagResultat.fra(
                korrigert.skjønnsfastsett(SkjønnsfastsettelseId(UUID.randomUUID()), listOf(Skjønnsfastsettelse(A1, BigDecimal("400000")))),
            )

        val arbeidsgiver = resultat.arbeidsgivere.single()
        assertEquals(Resultatkilde.SAKSBEHANDLER, arbeidsgiver.inntektskilde)
        assertEquals(BigDecimal("372000.00"), arbeidsgiver.omregnetÅrsinntekt)
        assertEquals(BigDecimal("400000.00"), arbeidsgiver.fastsattÅrsinntekt)
    }

    @Test
    fun `deaktivert arbeidsforhold er med, men fastsatt årsinntekt er 0`() {
        val resultat =
            SykepengegrunnlagResultat.fra(
                checkNotNull(
                    lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "30000"), lagArbeidsgiverinntekt(A2, månedlig = "10000"))
                        .overstyrArbeidsforhold(listOf(Arbeidsforholdoverstyring(A2, deaktivert = true))),
                ),
            )

        assertEquals(Opphav.OVERSTYRING_ARBEIDSFORHOLD, resultat.opphav)
        assertEquals(
            SykepengegrunnlagResultat.Arbeidsgiver(
                orgnummer = A2,
                inntektskilde = Resultatkilde.INNTEKTSMELDING,
                omregnetÅrsinntekt = BigDecimal("120000.00"),
                skjønnsfastsatt = null,
                fastsattÅrsinntekt = BigDecimal("0.00"),
                deaktivert = true,
            ),
            resultat.arbeidsgivere.single { it.orgnummer == A2 },
        )
        assertEquals(BigDecimal("360000.00"), resultat.beregningsgrunnlag)
    }

    @Test
    fun `grunnlag fra a-ordningen har kilden a-ordningen`() {
        val oppslag =
            AOrdningsoppslag(
                id = AOrdningsoppslag.Id(UUID.randomUUID()),
                måneder = Arbeidsgiverinntekt.beregningsperiodeForAOrdningen(SKJÆRINGSTIDSPUNKT),
                ainntektsfilter = "8-28Sykepenger",
                inntekter =
                    listOf(
                        AOrdningsinntekt(
                            virksomhet = A1.verdi,
                            skatteopplysning =
                                Skatteopplysning(
                                    måned = YearMonth.of(2025, 12),
                                    beløp = BigDecimal("30000"),
                                    type = Skatteopplysning.Inntekttype.LØNNSINNTEKT,
                                    fordel = "kontantytelse",
                                    beskrivelse = "fastloenn",
                                ),
                        ),
                    ),
                svar = "{}",
                hentet = Instant.now(),
            )
        val resultat = SykepengegrunnlagResultat.fra(lagGrunnlag(Arbeidsgiverinntekt.fraAOrdningen(A1, oppslag)))

        val arbeidsgiver = resultat.arbeidsgivere.single()
        assertEquals(Resultatkilde.A_ORDNINGEN, arbeidsgiver.inntektskilde)
        assertEquals(BigDecimal("120000.00"), arbeidsgiver.omregnetÅrsinntekt)
        assertEquals(BigDecimal("120000.00"), arbeidsgiver.fastsattÅrsinntekt)
    }

    @Test
    fun `virkningstidspunktet er datoen grunnbeløpet gjelder fra, også når det er senere enn reguleringen`() {
        val grunnlag =
            Sykepengegrunnlag.fastsett(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = LocalDate.of(2020, 10, 1),
                rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
                arbeidsgivere = listOf(lagArbeidsgiverinntekt(A1, månedlig = "30000")),
                nå = Instant.now(),
            )

        val resultat = SykepengegrunnlagResultat.fra(grunnlag)

        assertEquals(BigDecimal("101351.00"), resultat.grunnbeløp)
        assertEquals(LocalDate.of(2020, 9, 21), resultat.grunnbeløpVirkningstidspunkt)
    }

    @Test
    fun `grunnlag for selvstendig næringsdrivende har beregningsgrunnlaget og de pensjonsgivende inntektene`() {
        val grunnlag =
            Sykepengegrunnlag.fastsettForSelvstendig(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = LocalDate.of(2018, 6, 16),
                rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
                søknad = lagSøknad(2016 to 500000, 2015 to 450000, 2014 to 380000),
                nå = Instant.now(),
            )

        val resultat = SykepengegrunnlagResultat.fra(grunnlag)

        assertEquals(emptyList<SykepengegrunnlagResultat.Arbeidsgiver>(), resultat.arbeidsgivere)
        assertEquals(BigDecimal("478906.00"), resultat.beregningsgrunnlag)
        assertEquals(
            SykepengegrunnlagResultat.Selvstendig(
                beregningsgrunnlag = BigDecimal("478906.00"),
                fastsattÅrsinntekt = BigDecimal("478906.00"),
                pensjonsgivendeInntekter =
                    listOf(
                        SykepengegrunnlagResultat.PensjonsgivendeInntekt(2016, BigDecimal("500000.00")),
                        SykepengegrunnlagResultat.PensjonsgivendeInntekt(2015, BigDecimal("450000.00")),
                        SykepengegrunnlagResultat.PensjonsgivendeInntekt(2014, BigDecimal("380000.00")),
                    ),
            ),
            resultat.selvstendig,
        )
    }

    @Test
    fun `grunnlag for arbeidstaker har ikke selvstendig`() {
        assertNull(SykepengegrunnlagResultat.fra(lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "30000"))).selvstendig)
    }

    private fun lagGrunnlag(vararg arbeidsgivere: Arbeidsgiverinntekt) =
        Sykepengegrunnlag.fastsett(
            fødselsnummer = FØDSELSNUMMER,
            skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
            rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
            arbeidsgivere = arbeidsgivere.toList(),
            nå = Instant.now(),
        )

    private fun lagArbeidsgiverinntekt(
        orgnummer: Organisasjonsnummer,
        månedlig: String,
    ) = Arbeidsgiverinntekt.fraInntektsmelding(
        Inntektsmelding(
            internDokumentId = InternDokumentId(UUID.randomUUID()),
            inntektsmeldingId = InntektsmeldingId(UUID.randomUUID()),
            orgnummer = orgnummer,
            beregnetInntektMånedlig = BigDecimal(månedlig),
            mottattDato = Instant.now(),
            hentet = Instant.now(),
        ),
    )

    private fun lagSøknad(vararg næringsinntekter: Pair<Int, Int>) =
        Søknad(
            internDokumentId = InternDokumentId(UUID.randomUUID()),
            søknadId = SøknadId(UUID.randomUUID()),
            pensjonsgivendeInntekter =
                næringsinntekter.map { (år, beløp) ->
                    PensjonsgivendeInntekt(
                        år = år,
                        erFerdigLignet = true,
                        lønnsinntekt = 0,
                        lønnsinntektBarePensjonsdel = 0,
                        næringsinntekt = beløp,
                        næringsinntektFraFiskeFangstEllerFamiliebarnehage = 0,
                    )
                },
            hentet = Instant.now(),
        )

    private companion object {
        val A1 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val A2 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val FØDSELSNUMMER = Identitetsnummer.fraString(lagIdentitetsnummer())
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
