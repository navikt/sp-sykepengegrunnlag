package no.nav.helse.sykepenger.sykepengegrunnlag.api

import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangSomMangler
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.testing.testApplication
import kotliquery.sessionOf
import no.nav.helse.sykepenger.sykepengegrunnlag.Arbeidsgiverinntekt
import no.nav.helse.sykepenger.sykepengegrunnlag.Identitetsnummer
import no.nav.helse.sykepenger.sykepengegrunnlag.Inntektsmelding
import no.nav.helse.sykepenger.sykepengegrunnlag.InntektsmeldingId
import no.nav.helse.sykepenger.sykepengegrunnlag.InternDokumentId
import no.nav.helse.sykepenger.sykepengegrunnlag.Organisasjonsnummer
import no.nav.helse.sykepenger.sykepengegrunnlag.PgSykepengegrunnlagRepository
import no.nav.helse.sykepenger.sykepengegrunnlag.RettighetskategoriId
import no.nav.helse.sykepenger.sykepengegrunnlag.Skjønnsfastsettelse
import no.nav.helse.sykepenger.sykepengegrunnlag.SkjønnsfastsettelseId
import no.nav.helse.sykepenger.sykepengegrunnlag.Sykepengegrunnlag
import no.nav.helse.sykepenger.sykepengegrunnlag.TestDatabase
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_AUTHENTICATION_NAME
import no.nav.sykepenger.libs.ktor.auth.AccessToken
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.Saksbehandler
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerOid
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.ktor.openapi.assertOpenApiSnapshot
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer as PseudoIdIdentitetsnummer

internal class SykepengegrunnlagApiTest {
    private val fødselsnummer = lagIdentitetsnummer()
    private val skjæringstidspunkt = LocalDate.of(2026, 1, 1)
    private val orgnummer = "947064649"
    private var tilganger: Set<Tilgang>? = setOf(Tilgang.Les)

    private val personProvider = FakePersonProvider()
    private val pseudoId = personProvider.nyPersonPseudoId(PseudoIdIdentitetsnummer(fødselsnummer))
    private val path = "/api/personer/$pseudoId/sykepengegrunnlag?skjaeringstidspunkt=$skjæringstidspunkt"
    private val tilgangskontroll = FakeTilgangskontroll()

    @BeforeEach
    fun tømDatabase() {
        TestDatabase.tøm()
    }

    private fun Application.settOpp() {
        install(Authentication) {
            provider(AZURE_AD_AUTHENTICATION_NAME) {
                authenticate { context ->
                    tilganger?.let { context.principal(SaksbehandlerPrincipal(Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Saksbehandler"), it, AccessToken("token"))) }
                }
            }
        }
        configureRestApi(PgSykepengegrunnlagshistorikk(TestDatabase.dataSource), personProvider, tilgangskontroll)
    }

    @Test
    fun `gir alle grunnlagene for skjæringstidspunktet, med det nyeste først`() =
        testApplication {
            val vilkårsprøving = lagreGrunnlag()
            val skjønnsfastsettelseId = UUID.randomUUID()
            val skjønnsfastsatt =
                vilkårsprøving.skjønnsfastsett(
                    SkjønnsfastsettelseId(skjønnsfastsettelseId),
                    listOf(Skjønnsfastsettelse(Organisasjonsnummer.fraString(orgnummer), BigDecimal("400000"))),
                )
            lagre(skjønnsfastsatt)
            application { settOpp() }

            val response = client.get(path)

            assertEquals(HttpStatusCode.OK, response.status)
            val grunnlag = response.json()["sykepengegrunnlag"]
            assertEquals(listOf(skjønnsfastsatt.id.uuid.toString(), vilkårsprøving.id.uuid.toString()), grunnlag.values().map { it["id"].asString() })
            assertEquals(listOf("SKJONNSFASTSETTELSE", "VILKARSPROVING"), grunnlag.values().map { it["opphav"].asString() })

            val nyeste = grunnlag[0]
            assertEquals("2026-01-01", nyeste["skjæringstidspunkt"].asString())
            assertEquals(BigDecimal("400000.00").stripTrailingZeros(), nyeste["beregningsgrunnlag"].decimalValue().stripTrailingZeros())
            assertEquals(BigDecimal("400000.00").stripTrailingZeros(), nyeste["sykepengegrunnlag"].decimalValue().stripTrailingZeros())
            assertEquals(false, nyeste["er6GBegrenset"].asBoolean())
            assertTrue(nyeste["selvstendig"].isNull)
            val arbeidsgiver = nyeste["arbeidsgivere"].single()
            assertEquals(orgnummer, arbeidsgiver["orgnummer"].asString())
            assertEquals("INNTEKTSMELDING", arbeidsgiver["inntektskilde"].asString())
            assertEquals(false, arbeidsgiver["deaktivert"].asBoolean())
            assertEquals(BigDecimal("360000.00").stripTrailingZeros(), arbeidsgiver["omregnetÅrsinntekt"].decimalValue().stripTrailingZeros())
            assertEquals(BigDecimal("400000.00").stripTrailingZeros(), arbeidsgiver["fastsattÅrsinntekt"].decimalValue().stripTrailingZeros())
            assertEquals(skjønnsfastsettelseId.toString(), arbeidsgiver["skjønnsfastsatt"]["skjønnsfastsettelseId"].asString())
            assertEquals(BigDecimal("400000").stripTrailingZeros(), arbeidsgiver["skjønnsfastsatt"]["årsinntekt"].decimalValue().stripTrailingZeros())
        }

    @Test
    fun `gir en tom liste når skjæringstidspunktet ikke har noe grunnlag`() =
        testApplication {
            application { settOpp() }

            val response = client.get(path)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"sykepengegrunnlag":[]}""", response.bodyAsText())
        }

    @ParameterizedTest
    @ValueSource(strings = ["", "?skjaeringstidspunkt=", "?skjaeringstidspunkt=2026-13-01"])
    fun `gir 400 uten gyldig skjæringstidspunkt`(query: String) =
        testApplication {
            application { settOpp() }

            assertProblem(HttpStatusCode.BadRequest, client.get("/api/personer/$pseudoId/sykepengegrunnlag$query"))
        }

    @ParameterizedTest
    @ValueSource(strings = ["ikke-en-uuid", "6f1b1a4e-2f0c-4a2e-9a55-6d6a0a2c1b11"])
    fun `gir 404 for en ukjent person`(personPseudoId: String) =
        testApplication {
            application { settOpp() }

            assertProblem(HttpStatusCode.NotFound, client.get("/api/personer/$personPseudoId/sykepengegrunnlag?skjaeringstidspunkt=$skjæringstidspunkt"))
        }

    @Test
    fun `gir 401 uten innlogget saksbehandler`() =
        testApplication {
            tilganger = null
            application { settOpp() }

            assertProblem(HttpStatusCode.Unauthorized, client.get(path))
        }

    @Test
    fun `gir 403 uten lesetilgang`() =
        testApplication {
            tilganger = emptySet()
            application { settOpp() }

            assertProblem(HttpStatusCode.Forbidden, client.get(path))
        }

    @Test
    fun `gir 403 når tilgangsmaskinen avslår`() =
        testApplication {
            tilgangskontroll.resultat = TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.entries.first())
            application { settOpp() }

            assertProblem(HttpStatusCode.Forbidden, client.get(path))
        }

    @Test
    fun `gir 404 når tilgangsmaskinen ikke finner personen`() =
        testApplication {
            tilgangskontroll.resultat = TilgangskontrollResultat.IdentIkkeFunnet
            application { settOpp() }

            assertProblem(HttpStatusCode.NotFound, client.get(path))
        }

    @Test
    fun `OpenAPI-spesifikasjonen er lik snapshoten`() =
        testApplication {
            application { settOpp() }

            val response = client.get("/api/openapi.json")

            assertEquals(HttpStatusCode.OK, response.status)
            assertOpenApiSnapshot(response.bodyAsText(), Path.of("src/test/resources/openapi/sp-sykepengegrunnlag.json"))
        }

    private suspend fun assertProblem(
        status: HttpStatusCode,
        response: HttpResponse,
    ) {
        assertEquals(status, response.status)
        assertEquals("application/problem+json", response.headers["Content-Type"]?.substringBefore(";"))
        val problem = response.json()
        assertEquals(status.value, problem["status"].asInt())
        assertTrue(problem["title"].asString().isNotBlank())
        assertTrue(problem["detail"].asString().isNotBlank())
    }

    private suspend fun HttpResponse.json(): JsonNode = jacksonObjectMapper().readTree(bodyAsText())

    private fun lagreGrunnlag(): Sykepengegrunnlag {
        val inntektsmelding =
            Inntektsmelding(
                internDokumentId = InternDokumentId(UUID.randomUUID()),
                inntektsmeldingId = InntektsmeldingId(UUID.randomUUID()),
                orgnummer = Organisasjonsnummer.fraString(orgnummer),
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
        lagre(grunnlag, listOf(inntektsmelding))
        return grunnlag
    }

    private fun lagre(
        grunnlag: Sykepengegrunnlag,
        inntektsmeldinger: List<Inntektsmelding> = emptyList(),
    ) {
        sessionOf(TestDatabase.dataSource).use { session ->
            session.transaction { PgSykepengegrunnlagRepository(it).lagre(grunnlag, inntektsmeldinger, null) }
        }
    }
}
