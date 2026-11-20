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

    private companion object {
        val A1 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val A2 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val A3 = Organisasjonsnummer.fraString(lagOrganisasjonsnummer())
        val FØDSELSNUMMER = Identitetsnummer.fraString(lagIdentitetsnummer())
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
        val SKJØNNSFASTSETTELSE_ID = SkjønnsfastsettelseId(UUID.randomUUID())
    }
}
