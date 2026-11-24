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

internal class OverstyrArbeidsforholdRiverTest {
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
    fun `lagrer et nytt grunnlag med deaktivert arbeidsforhold og sender endret_sykepengegrunnlag`() {
        val forrige = lagreGrunnlag()

        rapid.sendTestMessage(lagOverstyring(A2 to true), FØDSELSNUMMER)

        val nytt = checkNotNull(finnNyeste())
        assertEquals(Opphav.OVERSTYRING_ARBEIDSFORHOLD, nytt.opphav)
        assertEquals(listOf(false, true), nytt.arbeidsgivere.map { it.deaktivert })
        assertEquals(BigDecimal("360000.00"), nytt.beregningsgrunnlag)

        assertEquals(1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(0))
        val melding = rapid.inspektør.message(0)
        assertEquals("endret_sykepengegrunnlag", melding.path("@event_name").asString())
        assertEquals(FØDSELSNUMMER, melding.path("fødselsnummer").asString())
        assertEquals(SKJÆRINGSTIDSPUNKT.toString(), melding.path("skjæringstidspunkt").asString())
        assertEquals(nytt.id.uuid.toString(), melding.path("sykepengegrunnlagId").asString())
        assertEquals(forrige.id.uuid.toString(), melding.path("forrigeSykepengegrunnlagId").asString())
        assertEquals("overstyring_arbeidsforhold", melding.path("opphav").asString())
        assertEquals(true, melding.path("manuellVurdering").asBoolean())
    }

    @Test
    fun `teller og ignorerer overstyringen når det ikke finnes noe grunnlag`() {
        rapid.sendTestMessage(lagOverstyring(A2 to true), FØDSELSNUMMER)

        assertEquals(null, finnNyeste())
        assertEquals(0, rapid.inspektør.size)
        assertEquals(1.0, meterRegistry.counter("sykepengegrunnlag_endring_uten_grunnlag", "opphav", "overstyring_arbeidsforhold").count())
    }

    private fun finnNyeste() =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).finnNyesteOrNull(Identitetsnummer.fraString(FØDSELSNUMMER), SKJÆRINGSTIDSPUNKT) }
        }

    private fun lagreGrunnlag(): Sykepengegrunnlag {
        val inntektsmeldinger = listOf(A1, A2).map { lagInntektsmelding(it) }
        val grunnlag =
            Sykepengegrunnlag.fastsett(
                fødselsnummer = Identitetsnummer.fraString(FØDSELSNUMMER),
                skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
                arbeidsgivere = inntektsmeldinger.map { Arbeidsgiverinntekt.fraInntektsmelding(it) },
                nå = Instant.now(),
            )
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(grunnlag, inntektsmeldinger, aOrdningsoppslag = null) }
        }
        return grunnlag
    }

    private fun lagInntektsmelding(orgnummer: String) =
        Inntektsmelding(
            internDokumentId = InternDokumentId(UUID.randomUUID()),
            inntektsmeldingId = InntektsmeldingId(UUID.randomUUID()),
            orgnummer = Organisasjonsnummer.fraString(orgnummer),
            beregnetInntektMånedlig = BigDecimal("30000"),
            mottattDato = Instant.now(),
            hentet = Instant.now(),
        )

    @Language("JSON")
    private fun lagOverstyring(vararg arbeidsforhold: Pair<String, Boolean>) =
        """
        {
          "@event_name": "overstyr_arbeidsforhold",
          "@id": "${UUID.randomUUID()}",
          "@opprettet": "${LocalDateTime.now()}",
          "fødselsnummer": "$FØDSELSNUMMER",
          "aktørId": "1234567891011",
          "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
          "overstyrteArbeidsforhold": [
            ${arbeidsforhold.joinToString { (orgnummer, deaktivert) ->
            """{"orgnummer": "$orgnummer", "deaktivert": $deaktivert, "begrunnelse": "Ikke aktivt arbeidsforhold", "forklaring": "Sluttet før skjæringstidspunktet"}"""
        }}
          ],
          "saksbehandlerOid": "${UUID.randomUUID()}",
          "saksbehandlerNavn": "Saks Behandler",
          "saksbehandlerIdent": "X999999",
          "saksbehandlerEpost": "saks.behandler@nav.no"
        }
        """

    private companion object {
        val FØDSELSNUMMER = lagIdentitetsnummer()
        val A1 = lagOrganisasjonsnummer()
        val A2 = lagOrganisasjonsnummer()
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
