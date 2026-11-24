package no.nav.helse.sykepenger.sykepengegrunnlag

import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class SykepengegrunnlagTest {
    @Test
    fun `skjønnsfastsettelse gir et nytt grunnlag der beregningsgrunnlaget er summen av de skjønnsfastsatte beløpene`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))

        val nytt =
            forrige.skjønnsfastsett(
                skjønnsfastsettelseId = SKJØNNSFASTSETTELSE_ID,
                fastsettelser = listOf(lagSkjønnsfastsettelse(A1, "350000"), lagSkjønnsfastsettelse(A2, "250000")),
            )

        assertNotEquals(forrige.id, nytt.id)
        assertEquals(Opphav.SKJØNNSFASTSETTELSE, nytt.opphav)
        assertEquals(BigDecimal("600000"), nytt.beregningsgrunnlag)
        assertEquals(BigDecimal("600000"), nytt.sykepengegrunnlag)
        assertEquals(false, nytt.er6GBegrenset)
        assertEquals(
            listOf(
                Skjønnsfastsatt(SKJØNNSFASTSETTELSE_ID, BigDecimal("350000")),
                Skjønnsfastsatt(SKJØNNSFASTSETTELSE_ID, BigDecimal("250000")),
            ),
            nytt.arbeidsgivere.map { it.skjønnsfastsatt },
        )
        assertEquals(listOf(BigDecimal("300000"), BigDecimal("240000")), nytt.arbeidsgivere.map { it.omregnetÅrsinntekt })
    }

    @Test
    fun `skjønnsfastsettelse er en manuell vurdering som erstatter det forrige grunnlaget`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"))

        val nytt = forrige.skjønnsfastsett(SKJØNNSFASTSETTELSE_ID, listOf(lagSkjønnsfastsettelse(A1, "350000")))

        assertEquals(
            listOf(
                EndretSykepengegrunnlag(
                    fødselsnummer = FØDSELSNUMMER,
                    skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                    sykepengegrunnlagId = nytt.id,
                    forrigeSykepengegrunnlagId = forrige.id,
                    opphav = Opphav.SKJØNNSFASTSETTELSE,
                    manuellVurdering = true,
                ),
            ),
            nytt.hendelser,
        )
        assertEquals(emptyList<EndretSykepengegrunnlag>(), forrige.hendelser)
    }

    @Test
    fun `skjønnsfastsatt grunnlag begrenses til 6G med grunnbeløpet grunnlaget er fastsatt med`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"))

        val nytt = forrige.skjønnsfastsett(SKJØNNSFASTSETTELSE_ID, listOf(lagSkjønnsfastsettelse(A1, "900000")))

        assertEquals(BigDecimal("900000"), nytt.beregningsgrunnlag)
        assertEquals(forrige.seksG, nytt.sykepengegrunnlag)
        assertEquals(forrige.grunnbeløp, nytt.grunnbeløp)
        assertEquals(true, nytt.er6GBegrenset)
    }

    @Test
    fun `alle arbeidsgiverne i grunnlaget må skjønnsfastsettes`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))

        assertThrows<IllegalStateException> { forrige.skjønnsfastsett(SKJØNNSFASTSETTELSE_ID, listOf(lagSkjønnsfastsettelse(A1, "350000"))) }
        assertThrows<IllegalStateException> {
            forrige.skjønnsfastsett(
                SKJØNNSFASTSETTELSE_ID,
                listOf(lagSkjønnsfastsettelse(A1, "350000"), lagSkjønnsfastsettelse(A2, "250000"), lagSkjønnsfastsettelse(A3, "100000")),
            )
        }
    }

    @Test
    fun `overstyring av inntekt gir et nytt grunnlag med overstyrt inntekt for arbeidsgiveren`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))

        val nytt =
            checkNotNull(
                forrige.overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A1, "330000"), lagInntektsoverstyring(A2, "240000"))),
            )

        assertNotEquals(forrige.id, nytt.id)
        assertEquals(Opphav.OVERSTYRING_INNTEKT, nytt.opphav)
        assertEquals(listOf(OverstyrtInntekt(OVERSTYRING_ID, BigDecimal("330000")), null), nytt.arbeidsgivere.map { it.overstyrtInntekt })
        assertEquals(listOf(BigDecimal("300000"), BigDecimal("240000")), nytt.arbeidsgivere.map { it.omregnetÅrsinntekt })
        assertEquals(0, BigDecimal("570000").compareTo(nytt.beregningsgrunnlag))
        assertEquals(0, BigDecimal("570000").compareTo(nytt.sykepengegrunnlag))
        assertEquals(
            listOf(
                EndretSykepengegrunnlag(
                    fødselsnummer = FØDSELSNUMMER,
                    skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                    sykepengegrunnlagId = nytt.id,
                    forrigeSykepengegrunnlagId = forrige.id,
                    opphav = Opphav.OVERSTYRING_INNTEKT,
                    manuellVurdering = true,
                ),
            ),
            nytt.hendelser,
        )
    }

    @Test
    fun `overstyring som ikke endrer inntekten gir ikke noe nytt grunnlag`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"))

        assertEquals(null, forrige.overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A1, "300000.00"))))
        assertEquals(null, forrige.overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A3, "500000"))))
    }

    @Test
    fun `overstyring sammenligner med inntekten saksbehandler har overstyrt fra før`() {
        val overstyrt = checkNotNull(lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000")).overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A1, "330000"))))
        val nyOverstyring = OverstyringId(UUID.randomUUID())

        assertEquals(null, overstyrt.overstyrInntekt(nyOverstyring, listOf(lagInntektsoverstyring(A1, "330000"))))
        val tilbake = checkNotNull(overstyrt.overstyrInntekt(nyOverstyring, listOf(lagInntektsoverstyring(A1, "300000"))))
        assertEquals(OverstyrtInntekt(nyOverstyring, BigDecimal("300000")), tilbake.arbeidsgivere.single().overstyrtInntekt)
    }

    @Test
    fun `overstyring av inntekten til én arbeidsgiver nullstiller skjønnsfastsettelsen for alle`() {
        val skjønnsfastsatt =
            lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))
                .skjønnsfastsett(SKJØNNSFASTSETTELSE_ID, listOf(lagSkjønnsfastsettelse(A1, "350000"), lagSkjønnsfastsettelse(A2, "250000")))

        val nytt = checkNotNull(skjønnsfastsatt.overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A1, "330000"), lagInntektsoverstyring(A2, "240000"))))

        assertEquals(listOf(null, null), nytt.arbeidsgivere.map { it.skjønnsfastsatt })
        assertEquals(0, BigDecimal("570000").compareTo(nytt.beregningsgrunnlag))
    }

    @Test
    fun `overstyring som ikke endrer inntekten beholder skjønnsfastsettelsen`() {
        val skjønnsfastsatt =
            lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"))
                .skjønnsfastsett(SKJØNNSFASTSETTELSE_ID, listOf(lagSkjønnsfastsettelse(A1, "350000")))

        assertEquals(null, skjønnsfastsatt.overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A1, "300000"))))
    }

    @Test
    fun `overstyrt inntekt over 6G begrenses med grunnbeløpet grunnlaget er fastsatt med`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"))

        val nytt = checkNotNull(forrige.overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A1, "900000"))))

        assertEquals(0, BigDecimal("900000").compareTo(nytt.beregningsgrunnlag))
        assertEquals(forrige.seksG, nytt.sykepengegrunnlag)
        assertEquals(forrige.grunnbeløp, nytt.grunnbeløp)
        assertEquals(true, nytt.er6GBegrenset)
    }

    @Test
    fun `deaktivering gir et nytt grunnlag der arbeidsforholdet er med som deaktivert, med fastsatt årsinntekt 0`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))

        val nytt = checkNotNull(forrige.overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = true))))

        assertNotEquals(forrige.id, nytt.id)
        assertEquals(Opphav.OVERSTYRING_ARBEIDSFORHOLD, nytt.opphav)
        assertEquals(listOf(false, true), nytt.arbeidsgivere.map { it.deaktivert })
        assertEquals(0, BigDecimal("300000").compareTo(nytt.arbeidsgivere[0].fastsattÅrsinntekt))
        assertEquals(0, BigDecimal.ZERO.compareTo(nytt.arbeidsgivere[1].fastsattÅrsinntekt))
        assertEquals(0, BigDecimal("240000").compareTo(nytt.arbeidsgivere[1].omregnetÅrsinntekt))
        assertEquals(0, BigDecimal("300000").compareTo(nytt.beregningsgrunnlag))
        assertEquals(0, BigDecimal("300000").compareTo(nytt.sykepengegrunnlag))
        assertEquals(
            listOf(
                EndretSykepengegrunnlag(
                    fødselsnummer = FØDSELSNUMMER,
                    skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                    sykepengegrunnlagId = nytt.id,
                    forrigeSykepengegrunnlagId = forrige.id,
                    opphav = Opphav.OVERSTYRING_ARBEIDSFORHOLD,
                    manuellVurdering = true,
                ),
            ),
            nytt.hendelser,
        )
    }

    @Test
    fun `aktivering teller arbeidsforholdet med igjen, med inntekten saksbehandler har overstyrt`() {
        val deaktivert =
            checkNotNull(
                checkNotNull(
                    lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))
                        .overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A2, "250000"))),
                ).overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = true))),
            )

        val aktivert = checkNotNull(deaktivert.overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = false))))

        assertEquals(listOf(false, false), aktivert.arbeidsgivere.map { it.deaktivert })
        assertEquals(0, BigDecimal("300000").compareTo(aktivert.arbeidsgivere[0].fastsattÅrsinntekt))
        assertEquals(0, BigDecimal("250000").compareTo(aktivert.arbeidsgivere[1].fastsattÅrsinntekt))
        assertEquals(0, BigDecimal("550000").compareTo(aktivert.beregningsgrunnlag))
    }

    @Test
    fun `overstyring som ikke endrer noe arbeidsforhold i grunnlaget gir ikke noe nytt grunnlag`() {
        val forrige = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))
        val deaktivert = checkNotNull(forrige.overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = true))))

        assertEquals(null, forrige.overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = false))))
        assertEquals(null, deaktivert.overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = true))))
        assertEquals(null, forrige.overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A3, deaktivert = true))))
    }

    @Test
    fun `overstyring av arbeidsforhold nullstiller skjønnsfastsettelsen for alle`() {
        val skjønnsfastsatt =
            lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))
                .skjønnsfastsett(SKJØNNSFASTSETTELSE_ID, listOf(lagSkjønnsfastsettelse(A1, "350000"), lagSkjønnsfastsettelse(A2, "250000")))

        val nytt = checkNotNull(skjønnsfastsatt.overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = true))))

        assertEquals(listOf(null, null), nytt.arbeidsgivere.map { it.skjønnsfastsatt })
        assertEquals(0, BigDecimal("300000").compareTo(nytt.beregningsgrunnlag))
    }

    @Test
    fun `overstyring av inntekt endrer ikke deaktiverte arbeidsforhold`() {
        val deaktivert =
            checkNotNull(
                lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))
                    .overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = true))),
            )

        assertEquals(null, deaktivert.overstyrInntekt(OVERSTYRING_ID, listOf(lagInntektsoverstyring(A2, "250000"))))
    }

    @Test
    fun `skjønnsfastsettelse gjelder bare de aktive arbeidsforholdene`() {
        val deaktivert =
            checkNotNull(
                lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"), lagArbeidsgiverinntekt(A2, månedlig = "20000"))
                    .overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A2, deaktivert = true))),
            )

        val nytt = deaktivert.skjønnsfastsett(SKJØNNSFASTSETTELSE_ID, listOf(lagSkjønnsfastsettelse(A1, "350000")))

        assertEquals(listOf(Skjønnsfastsatt(SKJØNNSFASTSETTELSE_ID, BigDecimal("350000")), null), nytt.arbeidsgivere.map { it.skjønnsfastsatt })
        assertEquals(listOf(false, true), nytt.arbeidsgivere.map { it.deaktivert })
        assertEquals(0, BigDecimal("350000").compareTo(nytt.beregningsgrunnlag))
        assertThrows<IllegalStateException> {
            deaktivert.skjønnsfastsett(SKJØNNSFASTSETTELSE_ID, listOf(lagSkjønnsfastsettelse(A1, "350000"), lagSkjønnsfastsettelse(A2, "250000")))
        }
    }

    @Test
    fun `finner arbeidsforholdene i overstyringen som ikke er i grunnlaget`() {
        val grunnlag = lagGrunnlag(lagArbeidsgiverinntekt(A1, månedlig = "25000"))

        assertEquals(listOf(A3), grunnlag.ukjenteArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A1), lagArbeidsforholdoverstyring(A3))))
        assertEquals(emptyList<Organisasjonsnummer>(), grunnlag.ukjenteArbeidsforhold(listOf(lagArbeidsforholdoverstyring(A1))))
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

    private fun lagSkjønnsfastsettelse(
        orgnummer: Organisasjonsnummer,
        årlig: String,
    ) = Skjønnsfastsettelse(orgnummer, BigDecimal(årlig))

    private fun lagArbeidsforholdoverstyring(
        orgnummer: Organisasjonsnummer,
        deaktivert: Boolean = true,
    ) = Arbeidsforholdoverstyring(orgnummer, deaktivert)

    private fun lagInntektsoverstyring(
        orgnummer: Organisasjonsnummer,
        årlig: String,
    ) = Inntektsoverstyring(orgnummer, BigDecimal(årlig))

    private companion object {
        val A1 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val A2 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val A3 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val FØDSELSNUMMER = Identitetsnummer.fraString(lagIdentitetsnummer())
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
        val SKJØNNSFASTSETTELSE_ID = SkjønnsfastsettelseId(UUID.randomUUID())
        val OVERSTYRING_ID = OverstyringId(UUID.randomUUID())
    }
}
