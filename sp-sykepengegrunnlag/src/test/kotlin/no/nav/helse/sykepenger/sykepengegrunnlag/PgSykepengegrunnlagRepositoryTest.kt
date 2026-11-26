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
        listOf(Opphav.BACKFILL_SPLEIS, Opphav.BACKFILL_INFOTRYGD).forEach { TestDatabase.lagreGrunnlagFraBackfill(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT, it) }

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

    @Test
    fun `lagrer det skjønnsfastsatte beløpet og ID-en til skjønnsfastsettelsen`() {
        val forrige = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        val skjønnsfastsettelseId = SkjønnsfastsettelseId(UUID.randomUUID())
        val skjønnsfastsatt = forrige.skjønnsfastsett(skjønnsfastsettelseId, listOf(Skjønnsfastsettelse(forrige.arbeidsgivere.single().orgnummer, BigDecimal("400000"))))
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(skjønnsfastsatt, inntektsmeldinger = emptyList(), aOrdningsoppslag = null) }
        }

        val lagret = checkNotNull(finnNyesteUtenomBackfillOrNull())

        assertEquals(skjønnsfastsatt.id, lagret.id)
        assertEquals(Opphav.SKJØNNSFASTSETTELSE, lagret.opphav)
        assertEquals(Skjønnsfastsatt(skjønnsfastsettelseId, BigDecimal("400000.00")), lagret.arbeidsgivere.single().skjønnsfastsatt)
        assertEquals(BigDecimal("400000.00"), lagret.beregningsgrunnlag)
    }

    @Test
    fun `lagrer den overstyrte inntekten og ID-en til overstyringen`() {
        val forrige = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        val overstyringId = OverstyringId(UUID.randomUUID())
        val overstyrt = checkNotNull(forrige.overstyrInntekt(overstyringId, listOf(Inntektsoverstyring(forrige.arbeidsgivere.single().orgnummer, BigDecimal("372000")))))
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(overstyrt, inntektsmeldinger = emptyList(), aOrdningsoppslag = null) }
        }

        val lagret = checkNotNull(finnNyesteUtenomBackfillOrNull())

        assertEquals(overstyrt.id, lagret.id)
        assertEquals(Opphav.OVERSTYRING_INNTEKT, lagret.opphav)
        assertEquals(OverstyrtInntekt(overstyringId, BigDecimal("372000.00")), lagret.arbeidsgivere.single().overstyrtInntekt)
        assertEquals(
            forrige.arbeidsgivere
                .single()
                .omregnetÅrsinntekt
                .setScale(2),
            lagret.arbeidsgivere.single().omregnetÅrsinntekt,
        )
        assertEquals(BigDecimal("372000.00"), lagret.beregningsgrunnlag)
        assertEquals(2, TestDatabase.grunnlag().size)
    }

    @Test
    fun `lagrer at arbeidsforholdet er deaktivert`() {
        val forrige = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        val deaktivert = checkNotNull(forrige.overstyrArbeidsforhold(listOf(lagArbeidsforholdoverstyring(forrige.arbeidsgivere.single().orgnummer))))
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(deaktivert, inntektsmeldinger = emptyList(), aOrdningsoppslag = null) }
        }

        val lagret = checkNotNull(finnNyesteUtenomBackfillOrNull())

        assertEquals(deaktivert.id, lagret.id)
        assertEquals(Opphav.OVERSTYRING_ARBEIDSFORHOLD, lagret.opphav)
        assertEquals(true, lagret.arbeidsgivere.single().deaktivert)
        assertEquals(0, BigDecimal.ZERO.compareTo(lagret.arbeidsgivere.single().fastsattÅrsinntekt))
        assertEquals(BigDecimal("0.00"), lagret.beregningsgrunnlag)
    }

    @Test
    fun `arbeidsforhold i grunnlag lagret før deaktivering fantes, er aktive`() {
        val grunnlag = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        TestDatabase.fjernLagretFelt(grunnlag.id.uuid, "{arbeidsgivere,0,deaktivert}")

        assertEquals(false, checkNotNull(finnNyesteUtenomBackfillOrNull()).arbeidsgivere.single().deaktivert)
    }

    @Test
    fun `endringer bygger på det nyeste grunnlaget, også når det kommer fra backfill`() {
        val vilkårsprøving = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        val backfill = vilkårsprøving.somBackfill()
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(backfill, inntektsmeldinger = emptyList(), aOrdningsoppslag = null) }
        }
        lagre(lagIdentitetsnummer(), SKJÆRINGSTIDSPUNKT)

        val nyeste =
            sessionOf(TestDatabase.dataSource).use { session ->
                session.transaction { PgSykepengegrunnlagRepository(it).finnNyesteOrNull(Identitetsnummer.fraString(FØDSELSNUMMER), SKJÆRINGSTIDSPUNKT) }
            }

        assertEquals(backfill.id, nyeste?.id)
    }

    @Test
    fun `finner alle grunnlagene for personen og skjæringstidspunktet, også fra backfill, med det nyeste først`() {
        val første = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        val backfill = første.somBackfill()
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(backfill, inntektsmeldinger = emptyList(), aOrdningsoppslag = null) }
        }
        val nyeste = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        lagre(lagIdentitetsnummer(), SKJÆRINGSTIDSPUNKT)
        lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT.plusDays(1))

        val alle =
            sessionOf(TestDatabase.dataSource).use { session ->
                session.transaction { PgSykepengegrunnlagRepository(it).finnAlle(Identitetsnummer.fraString(FØDSELSNUMMER), SKJÆRINGSTIDSPUNKT) }
            }

        assertEquals(listOf(nyeste.id, backfill.id, første.id), alle.map { it.id })
    }

    @Test
    fun `finner ingen grunnlag når skjæringstidspunktet ikke har noen`() {
        lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)

        val alle =
            sessionOf(TestDatabase.dataSource).use { session ->
                session.transaction { PgSykepengegrunnlagRepository(it).finnAlle(Identitetsnummer.fraString(FØDSELSNUMMER), SKJÆRINGSTIDSPUNKT.minusDays(1)) }
            }

        assertEquals(emptyList<Sykepengegrunnlag>(), alle)
    }

    @Test
    fun `finner grunnlaget med ID-en, også fra backfill`() {
        val grunnlag = lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)
        val backfill = grunnlag.somBackfill()
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(backfill, inntektsmeldinger = emptyList(), aOrdningsoppslag = null) }
        }
        lagre(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)

        assertEquals(grunnlag.id, finnOrNull(grunnlag.id)?.id)
        assertEquals(Opphav.BACKFILL_SPLEIS, finnOrNull(backfill.id)?.opphav)
        assertNull(finnOrNull(Sykepengegrunnlag.Id(UUID.randomUUID())))
    }

    private fun finnOrNull(id: Sykepengegrunnlag.Id) =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).finnOrNull(id) }
        }

    private fun Sykepengegrunnlag.somBackfill() =
        Sykepengegrunnlag.fraLagring(
            id = Sykepengegrunnlag.Id(UUID.randomUUID()),
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            rettighetskategoriId = rettighetskategoriId,
            opphav = Opphav.BACKFILL_SPLEIS,
            arbeidsgivere = arbeidsgivere,
            selvstendig = selvstendig,
            grunnbeløp = grunnbeløp,
            seksG = seksG,
            beregningsgrunnlag = beregningsgrunnlag,
            sykepengegrunnlag = sykepengegrunnlag,
            er6GBegrenset = er6GBegrenset,
            lovreferanser = lovreferanser,
        )

    private fun lagArbeidsforholdoverstyring(
        orgnummer: Organisasjonsnummer,
        deaktivert: Boolean = true,
    ) = Arbeidsforholdoverstyring(orgnummer, deaktivert)

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
