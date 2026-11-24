package no.nav.helse.sykepenger.sykepengegrunnlag

import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

internal class SammenligningTest {
    @Test
    fun `mangler når appen ikke har fastsatt noe grunnlag`() {
        assertEquals(Sammenligning.Mangler, Sammenligning.sammenlign(null, spleis(sykepengegrunnlag = "360000")))
    }

    @Test
    fun `likt grunnlag`() {
        val grunnlag = grunnlag(arbeidsgiver(A1, "360000"))

        val sammenligning = Sammenligning.sammenlign(grunnlag, spleis(sykepengegrunnlag = "360000", arbeidsgivere = listOf(spleisArbeidsgiver(A1, "360000"))))

        assertEquals(Sammenligning.Lik(grunnlag), sammenligning)
    }

    @Test
    fun `forskjellene viser feltene som er ulike`() {
        val grunnlag = grunnlag(arbeidsgiver(A1, "360000"))

        val sammenligning = Sammenligning.sammenlign(grunnlag, spleis(sykepengegrunnlag = "372000", arbeidsgivere = listOf(spleisArbeidsgiver(A1, "372000", inntektskilde = "AOrdningen"))))

        assertEquals(
            listOf(
                "sykepengegrunnlag: sp-sykepengegrunnlag=360000.00, Spleis=372000.00",
                "arbeidsgivere[${A1.verdi}].omregnetÅrsinntekt: sp-sykepengegrunnlag=360000.00, Spleis=372000.00",
                "arbeidsgivere[${A1.verdi}].inntektskilde: sp-sykepengegrunnlag=Arbeidsgiver, Spleis=AOrdningen",
            ),
            forskjeller(sammenligning),
        )
    }

    @Test
    fun `beløp som bare er ulike på grunn av binærfeil fra Spleis, er like`() {
        val grunnlag = grunnlag(arbeidsgiver(A1, "360000"))

        val sammenligning = Sammenligning.sammenlign(grunnlag, spleis(sykepengegrunnlag = "359999.99999999994", arbeidsgivere = listOf(spleisArbeidsgiver(A1, "360000.0000000001"))))

        assertInstanceOf(Sammenligning.Lik::class.java, sammenligning)
    }

    @Test
    fun `grunnlag som Spleis har skjønnsfastsatt, er forskjellig`() {
        val grunnlag = grunnlag(arbeidsgiver(A1, "360000"))

        val sammenligning = Sammenligning.sammenlign(grunnlag, spleis(sykepengegrunnlag = "360000", fastsatt = "EtterSkjønn", arbeidsgivere = listOf(spleisArbeidsgiver(A1, "360000", skjønnsfastsatt = "400000"))))

        assertEquals(
            listOf(
                "fastsatt: sp-sykepengegrunnlag=EtterHovedregel, Spleis=EtterSkjønn",
                "arbeidsgivere[${A1.verdi}].skjønnsfastsatt: sp-sykepengegrunnlag=mangler, Spleis=400000.00",
            ),
            forskjeller(sammenligning),
        )
    }

    @Test
    fun `skjønnsfastsatt grunnlag er likt når Spleis har skjønnsfastsatt det samme`() {
        val grunnlag =
            grunnlag(arbeidsgiver(A1, "360000"), arbeidsgiver(A2, "240000"))
                .skjønnsfastsett(
                    SkjønnsfastsettelseId(UUID.randomUUID()),
                    listOf(Skjønnsfastsettelse(A1, BigDecimal("400000")), Skjønnsfastsettelse(A2, BigDecimal("300000"))),
                )

        val sammenligning =
            Sammenligning.sammenlign(
                grunnlag,
                spleis(
                    sykepengegrunnlag = "700000",
                    fastsatt = "EtterSkjønn",
                    arbeidsgivere =
                        listOf(
                            spleisArbeidsgiver(A1, "360000", inntektskilde = "Saksbehandler", skjønnsfastsatt = "400000"),
                            spleisArbeidsgiver(A2, "240000", inntektskilde = "Saksbehandler", skjønnsfastsatt = "300000"),
                        ),
                ),
            )

        assertInstanceOf(Sammenligning.Lik::class.java, sammenligning)
    }

    @Test
    fun `overstyrt inntekt sammenlignes som omregnet årsinntekt med kilden Saksbehandler, slik Spleis viser den`() {
        val grunnlag =
            checkNotNull(
                grunnlag(arbeidsgiver(A1, "360000")).overstyrInntekt(OverstyringId(UUID.randomUUID()), listOf(Inntektsoverstyring(A1, BigDecimal("372000")))),
            )

        val sammenligning =
            Sammenligning.sammenlign(
                grunnlag,
                spleis(sykepengegrunnlag = "372000", arbeidsgivere = listOf(spleisArbeidsgiver(A1, "372000", inntektskilde = "Saksbehandler"))),
            )

        assertInstanceOf(Sammenligning.Lik::class.java, sammenligning)
    }

    @Test
    fun `arbeidsgivere som bare finnes på den ene siden, er en forskjell`() {
        val grunnlag = grunnlag(arbeidsgiver(A1, "360000"))

        val sammenligning = Sammenligning.sammenlign(grunnlag, spleis(sykepengegrunnlag = "360000", arbeidsgivere = listOf(spleisArbeidsgiver(A2, "360000"))))

        assertEquals(
            listOf(
                "arbeidsgivere[${A1.verdi}]: sp-sykepengegrunnlag=finnes, Spleis=mangler",
                "arbeidsgivere[${A2.verdi}]: sp-sykepengegrunnlag=mangler, Spleis=finnes",
            ),
            forskjeller(sammenligning),
        )
    }

    @Test
    fun `selvstendig næringsdrivende sammenlignes på de pensjonsgivende inntektene`() {
        val grunnlag = grunnlagForSelvstendig(2016 to 500000, 2015 to 450000)

        assertInstanceOf(
            Sammenligning.Lik::class.java,
            Sammenligning.sammenlign(grunnlag, spleis(sykepengegrunnlag = "478906", pensjonsgivendeInntekter = listOf(2016 to "500000", 2015 to "450000"))),
        )
        assertEquals(
            listOf("pensjonsgivendeInntekter[2015]: sp-sykepengegrunnlag=450000.00, Spleis=451000.00"),
            forskjeller(Sammenligning.sammenlign(grunnlag, spleis(sykepengegrunnlag = "478906", pensjonsgivendeInntekter = listOf(2016 to "500000", 2015 to "451000")))),
        )
    }

    @Test
    fun `har månedene fra a-ordningen bare for arbeidsgivere med forskjell`() {
        val måneder = listOf(skatteopplysning(YearMonth.of(2025, 12), "30000"))
        val grunnlag = grunnlag(arbeidsgiver(A1, "360000", skatteopplysninger = måneder), arbeidsgiver(A2, "360000", skatteopplysninger = måneder))

        val sammenligning =
            Sammenligning.sammenlign(
                grunnlag,
                spleis(sykepengegrunnlag = "720000", arbeidsgivere = listOf(spleisArbeidsgiver(A1, "372000", "AOrdningen"), spleisArbeidsgiver(A2, "360000", "AOrdningen"))),
            )

        assertEquals(mapOf(A1 to måneder), (sammenligning as Sammenligning.Forskjellig).månederFraAOrdningenForArbeidsgivereMedForskjell)
    }

    @Test
    fun `har ikke månedene for arbeidsgivere med inntektsmelding`() {
        val grunnlag = grunnlag(arbeidsgiver(A1, "360000"))

        val sammenligning = Sammenligning.sammenlign(grunnlag, spleis(sykepengegrunnlag = "372000", arbeidsgivere = listOf(spleisArbeidsgiver(A1, "372000"))))

        assertEquals(emptyMap<Organisasjonsnummer, List<Skatteopplysning>>(), (sammenligning as Sammenligning.Forskjellig).månederFraAOrdningenForArbeidsgivereMedForskjell)
    }

    private fun forskjeller(sammenligning: Sammenligning) = (sammenligning as Sammenligning.Forskjellig).forskjeller.map { it.toString() }

    private fun grunnlag(vararg arbeidsgivere: Arbeidsgiverinntekt): Sykepengegrunnlag {
        val beregningsgrunnlag = arbeidsgivere.fold(BigDecimal.ZERO) { sum, it -> sum + it.omregnetÅrsinntekt }
        return Sykepengegrunnlag.fraLagring(
            id = Sykepengegrunnlag.Id(UUID.randomUUID()),
            fødselsnummer = FØDSELSNUMMER,
            skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
            rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
            opphav = Opphav.VILKÅRSPRØVING,
            arbeidsgivere = arbeidsgivere.toList(),
            selvstendig = null,
            grunnbeløp = GRUNNBELØP,
            seksG = SEKS_G,
            beregningsgrunnlag = beregningsgrunnlag,
            sykepengegrunnlag = beregningsgrunnlag.min(SEKS_G),
            er6GBegrenset = beregningsgrunnlag > SEKS_G,
            lovreferanser = emptyList(),
        )
    }

    private fun grunnlagForSelvstendig(vararg inntekter: Pair<Int, Int>): Sykepengegrunnlag {
        val beregningsgrunnlag = BigDecimal("478906")
        val søknad = Søknad(InternDokumentId(UUID.randomUUID()), SøknadId(UUID.randomUUID()), emptyList(), Instant.now())
        return Sykepengegrunnlag.fraLagring(
            id = Sykepengegrunnlag.Id(UUID.randomUUID()),
            fødselsnummer = FØDSELSNUMMER,
            skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
            rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
            opphav = Opphav.VILKÅRSPRØVING,
            arbeidsgivere = emptyList(),
            selvstendig =
                Selvstendiginntekt.fraLagring(
                    søknad = søknad,
                    anvendtGrunnbeløp = GRUNNBELØP,
                    årsinntekter = inntekter.map { (år, beløp) -> Selvstendiginntekt.Årsinntekt(år, beløp, gjennomsnittligG = 100000) },
                    fastsattÅrsinntekt = beregningsgrunnlag,
                    beregningsgrunnlag = beregningsgrunnlag,
                ),
            grunnbeløp = GRUNNBELØP,
            seksG = SEKS_G,
            beregningsgrunnlag = beregningsgrunnlag,
            sykepengegrunnlag = beregningsgrunnlag,
            er6GBegrenset = false,
            lovreferanser = emptyList(),
        )
    }

    private fun arbeidsgiver(
        orgnummer: Organisasjonsnummer,
        omregnetÅrsinntekt: String,
        skatteopplysninger: List<Skatteopplysning> = emptyList(),
    ) = Arbeidsgiverinntekt(
        orgnummer = orgnummer,
        inntektskilde = if (skatteopplysninger.isEmpty()) Inntektskilde.INNTEKTSMELDING else Inntektskilde.A_ORDNINGEN,
        inntektsmeldingInternDokumentId = null,
        inntektsmeldingHentet = null,
        skatteopplysninger = skatteopplysninger,
        aOrdningsoppslagId = null,
        aOrdningenHentet = null,
        omregnetÅrsinntekt = BigDecimal(omregnetÅrsinntekt),
        overstyrtInntekt = null,
        skjønnsfastsatt = null,
    )

    private fun skatteopplysning(
        måned: YearMonth,
        beløp: String,
    ) = Skatteopplysning(måned, BigDecimal(beløp), Skatteopplysning.Inntekttype.LØNNSINNTEKT, "kontantytelse", "fastloenn")

    private fun spleis(
        sykepengegrunnlag: String,
        fastsatt: String = "EtterHovedregel",
        arbeidsgivere: List<Sykepengegrunnlagsfakta.Arbeidsgiver> = emptyList(),
        pensjonsgivendeInntekter: List<Pair<Int, String>> = emptyList(),
    ) = Sykepengegrunnlagsfakta(
        sykepengegrunnlag = Sykepengegrunnlagsfakta.beløp(BigDecimal(sykepengegrunnlag)),
        seksG = Sykepengegrunnlagsfakta.beløp(SEKS_G),
        fastsatt = fastsatt,
        arbeidsgivere = arbeidsgivere,
        pensjonsgivendeInntekter = pensjonsgivendeInntekter.map { (år, beløp) -> Sykepengegrunnlagsfakta.PensjonsgivendeInntekt(år, Sykepengegrunnlagsfakta.beløp(BigDecimal(beløp))) },
    )

    private fun spleisArbeidsgiver(
        orgnummer: Organisasjonsnummer,
        omregnetÅrsinntekt: String,
        inntektskilde: String = "Arbeidsgiver",
        skjønnsfastsatt: String? = null,
    ) = Sykepengegrunnlagsfakta.Arbeidsgiver(
        orgnummer = orgnummer,
        omregnetÅrsinntekt = Sykepengegrunnlagsfakta.beløp(BigDecimal(omregnetÅrsinntekt)),
        inntektskilde = inntektskilde,
        skjønnsfastsatt = skjønnsfastsatt?.let { Sykepengegrunnlagsfakta.beløp(BigDecimal(it)) },
    )

    private companion object {
        val A1 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val A2 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val FØDSELSNUMMER = Identitetsnummer.fraString(lagIdentitetsnummer())
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
        val GRUNNBELØP = BigDecimal("130160")
        val SEKS_G = BigDecimal("780960")
    }
}
