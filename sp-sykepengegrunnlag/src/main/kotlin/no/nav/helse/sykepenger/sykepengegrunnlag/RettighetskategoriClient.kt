package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.getOrThrow
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

internal class RettighetskategoriClient(
    private val baseUrl: String,
    private val scope: String,
    private val tokenProvider: AzureTokenProvider,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    fun hentHovedkategori(
        rettighetskategoriId: RettighetskategoriId,
        fødselsnummer: Identitetsnummer,
    ): Hovedkategori {
        val token = tokenProvider.bearerToken(scope).getOrThrow().token
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI("$baseUrl/api/rettighetskategorier/${rettighetskategoriId.uuid}"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .header("callId", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(mapOf("fødselsnummer" to fødselsnummer.verdi))))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "sp-rettighetskategori svarte HTTP ${response.statusCode()}" }
        return Hovedkategori.fraString(objectMapper.readTree(response.body()).path("hovedkategori").asString())
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()

        fun fraMiljø(
            env: Map<String, String>,
            tokenProvider: AzureTokenProvider,
        ) = RettighetskategoriClient(
            baseUrl = env.getValue("SP_RETTIGHETSKATEGORI_BASE_URL"),
            scope = env.getValue("SP_RETTIGHETSKATEGORI_SCOPE"),
            tokenProvider = tokenProvider,
        )
    }
}

internal enum class Hovedkategori {
    ARBEIDSTAKER,
    SELVSTENDIG_NAERINGSDRIVENDE,
    ARBEIDSTAKER_OG_SELVSTENDIG_NAERINGSDRIVENDE,
    ;

    companion object {
        fun fraString(hovedkategori: String) = requireNotNull(entries.find { it.name == hovedkategori }) { "Ukjent hovedkategori: $hovedkategori" }
    }
}
