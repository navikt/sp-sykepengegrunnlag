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

internal class SkjønnsmessigFastsettelseRiverTest {
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
    fun `lagrer et nytt skjønnsfastsatt grunnlag og sender endret_sykepengegrunnlag`() {
        val forrige = lagreGrunnlag()
        val skjønnsfastsettelseId = UUID.randomUUID()

        rapid.sendTestMessage(lagSkjønnsmessigFastsettelse(skjønnsfastsettelseId, årlig = 400000.0), FØDSELSNUMMER)

        val nytt = checkNotNull(finnNyeste())
        assertEquals(Opphav.SKJØNNSFASTSETTELSE, nytt.opphav)
        assertEquals(Skjønnsfastsatt(SkjønnsfastsettelseId(skjønnsfastsettelseId), BigDecimal("400000.00")), nytt.arbeidsgivere.single().skjønnsfastsatt)

        assertEquals(1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(0))
        val melding = rapid.inspektør.message(0)
        assertEquals("endret_sykepengegrunnlag", melding.path("@event_name").asString())
        assertEquals(FØDSELSNUMMER, melding.path("fødselsnummer").asString())
        assertEquals(SKJÆRINGSTIDSPUNKT.toString(), melding.path("skjæringstidspunkt").asString())
        assertEquals(nytt.id.uuid.toString(), melding.path("sykepengegrunnlagId").asString())
        assertEquals(forrige.id.uuid.toString(), melding.path("forrigeSykepengegrunnlagId").asString())
        assertEquals("skjønnsfastsettelse", melding.path("opphav").asString())
        assertEquals(true, melding.path("manuellVurdering").asBoolean())
    }

    @Test
    fun `teller og ignorerer skjønnsfastsettelsen når det ikke finnes noe grunnlag`() {
        rapid.sendTestMessage(lagSkjønnsmessigFastsettelse(UUID.randomUUID(), årlig = 400000.0), FØDSELSNUMMER)

        assertEquals(null, finnNyeste())
        assertEquals(0, rapid.inspektør.size)
        assertEquals(1.0, meterRegistry.counter("sykepengegrunnlag_endring_uten_grunnlag", "opphav", "skjønnsfastsettelse").count())
    }

    private fun finnNyeste() =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).finnNyesteOrNull(Identitetsnummer.fraString(FØDSELSNUMMER), SKJÆRINGSTIDSPUNKT) }
        }

    private fun lagreGrunnlag(
        orgnummer: String = ORGNUMMER,
        beregnetInntektMånedlig: String = "30000",
    ): Sykepengegrunnlag {
        val inntektsmelding =
            Inntektsmelding(
                internDokumentId = InternDokumentId(UUID.randomUUID()),
                inntektsmeldingId = InntektsmeldingId(UUID.randomUUID()),
                orgnummer = Organisasjonsnummer.fraString(orgnummer),
                beregnetInntektMånedlig = BigDecimal(beregnetInntektMånedlig),
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
    private fun lagSkjønnsmessigFastsettelse(
        id: UUID = UUID.randomUUID(),
        årlig: Double = 400000.0,
    ) = """
        {
          "@event_name": "skjønnsmessig_fastsettelse",
          "@id": "$id",
          "@opprettet": "${LocalDateTime.now()}",
          "fødselsnummer": "$FØDSELSNUMMER",
          "aktørId": "1234567891011",
          "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
          "arbeidsgivere": [{"organisasjonsnummer": "$ORGNUMMER", "årlig": $årlig, "fraÅrlig": 360000.0}],
          "saksbehandlerOid": "${UUID.randomUUID()}",
          "saksbehandlerNavn": "Saks Behandler",
          "saksbehandlerIdent": "X999999",
          "saksbehandlerEpost": "saks.behandler@nav.no"
        }
        """

    private companion object {
        val FØDSELSNUMMER = lagIdentitetsnummer()
        val ORGNUMMER = lagOrganisasjonsnummer()
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
