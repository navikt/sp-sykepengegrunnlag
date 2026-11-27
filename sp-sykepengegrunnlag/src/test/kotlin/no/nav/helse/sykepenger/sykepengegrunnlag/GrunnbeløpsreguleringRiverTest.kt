package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotliquery.sessionOf
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

internal class GrunnbeløpsreguleringRiverTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val rapid =
        TestRapid(meterRegistry).apply {
            registrerRivers(TestDatabase.dataSource, FakeSpedisjon().client, FakeRettighetskategori().client, FakeInntektskomponenten().client)
        }

    @BeforeEach
    fun setup() {
        TestDatabase.tøm()
    }

    @Test
    fun `lagrer et nytt grunnlag med riktig grunnbeløp og sender endret_sykepengegrunnlag`() {
        val forrige = lagreGrunnlagMedGammeltGrunnbeløp()

        rapid.sendTestMessage(lagGrunnbeløpsregulering(), FØDSELSNUMMER)

        val nytt = checkNotNull(finnNyeste())
        assertEquals(Opphav.GRUNNBELØPSREGULERING, nytt.opphav)
        assertEquals(BigDecimal("101351"), nytt.grunnbeløp.stripTrailingZeros())

        assertEquals(1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(0))
        val melding = rapid.inspektør.message(0)
        assertEquals("endret_sykepengegrunnlag", melding.path("@event_name").asString())
        assertEquals(FØDSELSNUMMER, melding.path("fødselsnummer").asString())
        assertEquals(SKJÆRINGSTIDSPUNKT.toString(), melding.path("skjæringstidspunkt").asString())
        assertEquals(nytt.id.uuid.toString(), melding.path("sykepengegrunnlagId").asString())
        assertEquals(forrige.id.uuid.toString(), melding.path("forrigeSykepengegrunnlagId").asString())
        assertEquals("grunnbeløpsregulering", melding.path("opphav").asString())
        assertEquals(false, melding.path("manuellVurdering").asBoolean())
    }

    @Test
    fun `teller og ignorerer grunnbeløpsreguleringen når det ikke finnes noe grunnlag`() {
        rapid.sendTestMessage(lagGrunnbeløpsregulering(), FØDSELSNUMMER)

        assertEquals(null, finnNyeste())
        assertEquals(0, rapid.inspektør.size)
        assertEquals(1.0, meterRegistry.counter("sykepengegrunnlag_endring_uten_grunnlag", "opphav", "grunnbeløpsregulering").count())
    }

    private fun finnNyeste() =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).finnNyesteOrNull(Identitetsnummer.fraString(FØDSELSNUMMER), SKJÆRINGSTIDSPUNKT) }
        }

    private fun lagreGrunnlagMedGammeltGrunnbeløp(): Sykepengegrunnlag {
        val inntektsmelding =
            Inntektsmelding(
                internDokumentId = InternDokumentId(UUID.randomUUID()),
                inntektsmeldingId = InntektsmeldingId(UUID.randomUUID()),
                orgnummer = Organisasjonsnummer.fraString(ORGNUMMER),
                beregnetInntektMånedlig = BigDecimal("30000"),
                mottattDato = Instant.now(),
                hentet = Instant.now(),
            )
        val grunnlag =
            Sykepengegrunnlag.fastsett(
                fødselsnummer = Identitetsnummer.fraString(FØDSELSNUMMER),
                skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
                arbeidsgivere = listOf(Arbeidsgiverinntekt.fraInntektsmelding(inntektsmelding)),
                nå = FØR_GRUNNBELØPET_2020_FIKK_VIRKNING,
            )
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(grunnlag, listOf(inntektsmelding), null) }
        }
        return grunnlag
    }

    @Language("JSON")
    private fun lagGrunnbeløpsregulering() =
        """
        {
          "@event_name": "grunnbeløpsregulering",
          "@id": "${UUID.randomUUID()}",
          "@opprettet": "${LocalDateTime.now()}",
          "fødselsnummer": "$FØDSELSNUMMER",
          "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT"
        }
        """

    private companion object {
        val FØDSELSNUMMER = lagIdentitetsnummer()
        val ORGNUMMER = lagOrganisasjonsnummer()

        /** Grunnbeløpet for 2020 fikk virkning fra 21. september. Før det ble grunnlag fastsatt med grunnbeløpet for 2019. */
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2020, 6, 1)
        val FØR_GRUNNBELØPET_2020_FIKK_VIRKNING: Instant = Instant.parse("2020-06-01T10:00:00Z")
    }
}
