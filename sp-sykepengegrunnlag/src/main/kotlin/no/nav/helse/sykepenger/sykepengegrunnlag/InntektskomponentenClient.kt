package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.getOrThrow
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

internal class InntektskomponentenClient(
    private val baseUrl: String,
    private val scope: String,
    private val tokenProvider: AzureTokenProvider,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    fun hentInntekterForSykepengegrunnlag(
        fødselsnummer: Identitetsnummer,
        måneder: ClosedRange<YearMonth>,
    ): AOrdningsoppslag {
        val token = tokenProvider.bearerToken(scope).getOrThrow().token
        // Bare fødselsnummer skal sendes som ident. Med aktør-ID kan inntektskomponenten svare med aktør-ID-er.
        val body =
            mapOf(
                "ident" to mapOf("identifikator" to fødselsnummer.verdi, "aktoerType" to "NATURLIG_IDENT"),
                "ainntektsfilter" to "8-28Sykepenger",
                "formaal" to "Sykepenger",
                "maanedFom" to måneder.start.toString(),
                "maanedTom" to måneder.endInclusive.toString(),
            )
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI("$baseUrl/api/v1/hentinntektliste"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .header("Nav-Consumer-Id", "sp-sykepengegrunnlag")
                .header("Nav-Call-Id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Inntektskomponenten svarte HTTP ${response.statusCode()}" }
        val hentet = Instant.now()
        val inntekter =
            objectMapper
                .readTree(response.body())
                .path("arbeidsInntektMaaned")
                .toList()
                .flatMap { måned ->
                    val årMåned = YearMonth.parse(måned.path("aarMaaned").asString())
                    måned
                        .path("arbeidsInntektInformasjon")
                        .path("inntektListe")
                        .toList()
                        .map { it.tilInntekt(årMåned) }
                }
        return AOrdningsoppslag(inntekter, hentet)
    }

    private fun JsonNode.tilInntekt(måned: YearMonth): AOrdningsinntekt {
        val virksomhet = path("virksomhet")
        check(virksomhet.path("aktoerType").asString() != "AKTOER_ID") { "Inntektskomponenten svarte med aktør-ID" }
        return AOrdningsinntekt(
            virksomhet = virksomhet.path("identifikator").asString(),
            skatteopplysning =
                Skatteopplysning(
                    måned = måned,
                    beløp = path("beloep").decimalValue(),
                    type = Skatteopplysning.Inntekttype.fraInntektskomponenten(path("inntektType").asString()),
                    fordel = path("fordel").takeIf { it.isString }?.asString() ?: "",
                    beskrivelse = path("beskrivelse").takeIf { it.isString }?.asString() ?: "",
                ),
        )
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()

        fun fraMiljø(
            env: Map<String, String>,
            tokenProvider: AzureTokenProvider,
        ) = InntektskomponentenClient(
            baseUrl = env.getValue("INNTEKTSKOMPONENTEN_BASE_URL"),
            scope = env.getValue("INNTEKTSKOMPONENTEN_SCOPE"),
            tokenProvider = tokenProvider,
        )
    }
}

internal data class AOrdningsoppslag(
    val inntekter: List<AOrdningsinntekt>,
    val hentet: Instant,
)

internal data class AOrdningsinntekt(
    val virksomhet: String,
    val skatteopplysning: Skatteopplysning,
)
