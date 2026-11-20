package no.nav.helse.sykepenger.sykepengegrunnlag

import kotliquery.sessionOf
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class PgSykepengegrunnlagRepositoryTest {
    @BeforeEach
    fun setup() {
        TestDatabase.tøm()
    }

    @Test
    fun `finner det nyeste grunnlaget for personen og skjæringstidspunktet, utenom backfill`() {
        lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        val nyeste = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        lagre(lagIdentitetsnummer(), SKJÆRINGSTIDSPUNKT)
        lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT.plusDays(1))
        Opphav.entries.filter { it != Opphav.VILKÅRSPRØVING }.forEach { TestDatabase.lagreGrunnlagFraBackfill(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT, it) }

        assertEquals(nyeste.id, finnNyesteUtenomBackfillOrNull()?.id)
    }

    @Test
    fun `finner ikke grunnlag som bare finnes fra backfill`() {
        TestDatabase.lagreGrunnlagFraBackfill(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT, Opphav.BACKFILL_SPLEIS)

        assertNull(finnNyesteUtenomBackfillOrNull())
    }

    @Test
    fun `gjenoppretter grunnlaget med de lagrede beløpene, uten å regne på nytt`() {
        val grunnlag = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        TestDatabase.overskrivLagretFelt(grunnlag.id.uuid, "{sykepengegrunnlag}", "359999.99")
        TestDatabase.overskrivLagretFelt(grunnlag.id.uuid, "{seksG}", "744168.00")

        val lagret = checkNotNull(finnNyesteUtenomBackfillOrNull())

        assertEquals(BigDecimal("359999.99"), lagret.sykepengegrunnlag)
        assertEquals(BigDecimal("744168.00"), lagret.seksG)
        assertEquals(grunnlag.lovreferanser, lagret.lovreferanser)
    }

    private fun finnNyesteUtenomBackfillOrNull() =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).finnNyesteUtenomBackfillOrNull(Identitetsnummer.fraString(FØDSELSNUMMER), SKJÆRINGSTIDSPUNKT) }
        }

    private fun lagre(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Sykepengegrunnlag {
        val inntektsmelding =
            Inntektsmelding(
                internDokumentId = InternDokumentId(UUID.randomUUID()),
                inntektsmeldingId = InntektsmeldingId(UUID.randomUUID()),
                orgnummer = Organisasjonsnummer.fraString(lagOrganisasjonsnummer()),
                beregnetInntektMånedlig = BigDecimal("30000"),
                mottattDato = Instant.now(),
                hentet = Instant.now(),
            )
        val grunnlag =
            Sykepengegrunnlag.fastsett(
                fødselsnummer = Identitetsnummer.fraString(fødselsnummer),
                skjæringstidspunkt = skjæringstidspunkt,
                rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
                arbeidsgivere = listOf(Arbeidsgiverinntekt.fraInntektsmelding(inntektsmelding)),
                nå = Instant.now(),
            )
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(grunnlag, listOf(inntektsmelding), null) }
        }
        return grunnlag
    }

    private companion object {
        val FØDSELSNUMMER = lagIdentitetsnummer()
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
