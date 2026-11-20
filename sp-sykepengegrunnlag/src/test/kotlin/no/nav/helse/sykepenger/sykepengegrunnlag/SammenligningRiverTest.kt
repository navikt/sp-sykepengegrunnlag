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

internal class SammenligningRiverTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val rapid = TestRapid(meterRegistry).apply { SammenligningRiver(this, TestDatabase.dataSource) }

    @BeforeEach
    fun setup() {
        TestDatabase.tøm()
    }

    @Test
    fun `sammenligner det lagrede grunnlaget med godkjenningsbehovet og teller utfallet`() {
        val grunnlag = lagreGrunnlag()

        rapid.sendTestMessage(lagGodkjenningsbehov(grunnlag.seksG), FØDSELSNUMMER)

        assertEquals(1.0, meterRegistry.counter("sykepengegrunnlag_sammenligning_i_skyggedrift", "utfall", "lik").count())
    }

    private fun lagreGrunnlag(): Sykepengegrunnlag {
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
                nå = Instant.now(),
            )
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(grunnlag, listOf(inntektsmelding), null) }
        }
        return grunnlag
    }

    @Language("JSON")
    private fun lagGodkjenningsbehov(seksG: BigDecimal) =
        """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@opprettet": "${LocalDateTime.now()}",
          "@behov": ["Godkjenning"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "Godkjenning": {
            "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
            "vilkårsgrunnlagId": "${UUID.randomUUID()}",
            "sykepengegrunnlagsfakta": {
              "sykepengegrunnlag": 360000.0,
              "6G": ${seksG.toDouble()},
              "fastsatt": "EtterHovedregel",
              "arbeidsgivere": [{"arbeidsgiver": "$ORGNUMMER", "omregnetÅrsinntekt": 360000.0, "inntektskilde": "Arbeidsgiver"}]
            }
          }
        }
        """

    private companion object {
        val FØDSELSNUMMER = lagIdentitetsnummer()
        val ORGNUMMER = lagOrganisasjonsnummer()
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
