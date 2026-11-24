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

internal class OverstyrInntektOgRefusjonRiverTest {
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
    fun `lagrer et nytt grunnlag med overstyrt inntekt og sender endret_sykepengegrunnlag`() {
        val forrige = lagreGrunnlag(beregnetInntektMånedlig = "30000")
        val overstyringId = UUID.randomUUID()

        rapid.sendTestMessage(lagOverstyring(overstyringId, månedligInntekt = 31000.0), FØDSELSNUMMER)

        val nytt = checkNotNull(finnNyeste())
        assertEquals(Opphav.OVERSTYRING_INNTEKT, nytt.opphav)
        assertEquals(OverstyrtInntekt(OverstyringId(overstyringId), BigDecimal("372000.00")), nytt.arbeidsgivere.single().overstyrtInntekt)

        assertEquals(1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(0))
        val melding = rapid.inspektør.message(0)
        assertEquals("endret_sykepengegrunnlag", melding.path("@event_name").asString())
        assertEquals(FØDSELSNUMMER, melding.path("fødselsnummer").asString())
        assertEquals(SKJÆRINGSTIDSPUNKT.toString(), melding.path("skjæringstidspunkt").asString())
        assertEquals(nytt.id.uuid.toString(), melding.path("sykepengegrunnlagId").asString())
        assertEquals(forrige.id.uuid.toString(), melding.path("forrigeSykepengegrunnlagId").asString())
        assertEquals("overstyring_inntekt", melding.path("opphav").asString())
        assertEquals(true, melding.path("manuellVurdering").asBoolean())
    }

    @Test
    fun `overstyring som bare gjelder refusjon gir ikke noe nytt grunnlag`() {
        val forrige = lagreGrunnlag(beregnetInntektMånedlig = "30000")

        rapid.sendTestMessage(lagOverstyring(månedligInntekt = 30000.0), FØDSELSNUMMER)

        assertEquals(forrige.id, finnNyeste()?.id)
        assertEquals(0, rapid.inspektør.size)
        assertEquals(0.0, meterRegistry.counter("sykepengegrunnlag_endring_uten_grunnlag", "opphav", "overstyring_inntekt").count())
    }

    @Test
    fun `overstyring med Spleis sitt månedsbeløp for en årsinntekt som ikke går opp i 12 gir ikke noe nytt grunnlag`() {
        val forrige = lagreGrunnlagFraAOrdningen("30000", "30000", "30001")
        assertEquals(BigDecimal("360004.00"), forrige.arbeidsgivere.single().omregnetÅrsinntekt)

        // Speil sender Spleis sin `årlig / 12` som Double når saksbehandleren bare endrer refusjonen
        rapid.sendTestMessage(lagOverstyring(månedligInntekt = 360004.0 / 12), FØDSELSNUMMER)

        assertEquals(forrige.id, finnNyeste()?.id)
        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `overstyring med Spleis sitt månedsbeløp for en årsinntekt med øre gir ikke noe nytt grunnlag`() {
        val forrige = lagreGrunnlag(beregnetInntektMånedlig = "30000.10")

        rapid.sendTestMessage(lagOverstyring(månedligInntekt = 360001.2 / 12), FØDSELSNUMMER)

        assertEquals(forrige.id, finnNyeste()?.id)
        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `teller og ignorerer overstyringen når det ikke finnes noe grunnlag`() {
        rapid.sendTestMessage(lagOverstyring(månedligInntekt = 31000.0), FØDSELSNUMMER)

        assertEquals(null, finnNyeste())
        assertEquals(0, rapid.inspektør.size)
        assertEquals(1.0, meterRegistry.counter("sykepengegrunnlag_endring_uten_grunnlag", "opphav", "overstyring_inntekt").count())
    }

    private fun finnNyeste() =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).finnNyesteOrNull(Identitetsnummer.fraString(FØDSELSNUMMER), SKJÆRINGSTIDSPUNKT) }
        }

    private fun lagreGrunnlag(beregnetInntektMånedlig: String): Sykepengegrunnlag {
        val inntektsmelding =
            Inntektsmelding(
                internDokumentId = InternDokumentId(UUID.randomUUID()),
                inntektsmeldingId = InntektsmeldingId(UUID.randomUUID()),
                orgnummer = Organisasjonsnummer.fraString(ORGNUMMER),
                beregnetInntektMånedlig = BigDecimal(beregnetInntektMånedlig),
                mottattDato = Instant.now(),
                hentet = Instant.now(),
            )
        return lagreGrunnlag(Arbeidsgiverinntekt.fraInntektsmelding(inntektsmelding), listOf(inntektsmelding), aOrdningsoppslag = null)
    }

    private fun lagreGrunnlagFraAOrdningen(vararg månedsbeløp: String): Sykepengegrunnlag {
        val måneder = Arbeidsgiverinntekt.beregningsperiodeForAOrdningen(SKJÆRINGSTIDSPUNKT)
        val oppslag =
            AOrdningsoppslag(
                id = AOrdningsoppslag.Id(UUID.randomUUID()),
                måneder = måneder,
                ainntektsfilter = "8-28Sykepenger",
                inntekter =
                    månedsbeløp.mapIndexed { index, beløp ->
                        AOrdningsinntekt(
                            virksomhet = ORGNUMMER,
                            skatteopplysning =
                                Skatteopplysning(
                                    måned = måneder.start.plusMonths(index.toLong()),
                                    beløp = BigDecimal(beløp),
                                    type = Skatteopplysning.Inntekttype.LØNNSINNTEKT,
                                    fordel = "kontantytelse",
                                    beskrivelse = "fastloenn",
                                ),
                        )
                    },
                svar = "{}",
                hentet = Instant.now(),
            )
        return lagreGrunnlag(Arbeidsgiverinntekt.fraAOrdningen(Organisasjonsnummer.fraString(ORGNUMMER), oppslag), emptyList(), oppslag)
    }

    private fun lagreGrunnlag(
        arbeidsgiver: Arbeidsgiverinntekt,
        inntektsmeldinger: List<Inntektsmelding>,
        aOrdningsoppslag: AOrdningsoppslag?,
    ): Sykepengegrunnlag {
        val grunnlag =
            Sykepengegrunnlag.fastsett(
                fødselsnummer = Identitetsnummer.fraString(FØDSELSNUMMER),
                skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
                arbeidsgivere = listOf(arbeidsgiver),
                nå = Instant.now(),
            )
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(grunnlag, inntektsmeldinger, aOrdningsoppslag) }
        }
        return grunnlag
    }

    @Language("JSON")
    private fun lagOverstyring(
        id: UUID = UUID.randomUUID(),
        månedligInntekt: Double,
    ) = """
        {
          "@event_name": "overstyr_inntekt_og_refusjon",
          "@id": "$id",
          "@opprettet": "${LocalDateTime.now()}",
          "fødselsnummer": "$FØDSELSNUMMER",
          "aktørId": "1234567891011",
          "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
          "arbeidsgivere": [
            {
              "organisasjonsnummer": "$ORGNUMMER",
              "månedligInntekt": $månedligInntekt,
              "fraMånedligInntekt": 30000.0,
              "refusjonsopplysninger": [{"fom": "$SKJÆRINGSTIDSPUNKT", "tom": null, "beløp": 31000.0}],
              "fraRefusjonsopplysninger": [{"fom": "$SKJÆRINGSTIDSPUNKT", "tom": null, "beløp": 30000.0}],
              "begrunnelse": "Varig lønnsendring",
              "forklaring": "Ny lønn fra januar",
              "fom": null,
              "tom": null
            }
          ],
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
