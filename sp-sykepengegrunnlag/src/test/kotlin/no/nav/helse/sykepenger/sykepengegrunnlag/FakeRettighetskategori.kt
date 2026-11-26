package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.mock.MockHttpResponse
import com.github.navikt.tbd_libs.mock.bodyAsString
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class FakeRettighetskategori {
    private val rettighetskategorier = mutableMapOf<UUID, Pair<String, String>>()
    private var feiler = false
    var antallOppslag = 0
        private set

    val client =
        RettighetskategoriClient(
            baseUrl = "http://sp-rettighetskategori",
            scope = "api://test.tbd.sp-rettighetskategori/.default",
            tokenProvider = FakeTokenProvider,
            httpClient = FakeHttpClient(::hent),
        )

    fun feiler() {
        feiler = true
    }

    fun lagRettighetskategori(
        fødselsnummer: String,
        hovedkategori: String = "ARBEIDSTAKER",
    ): UUID = UUID.randomUUID().also { rettighetskategorier[it] = fødselsnummer to hovedkategori }

    private fun hent(request: HttpRequest): HttpResponse<String> {
        val id = UUID.fromString(request.uri().path.removePrefix("/api/rettighetskategorier/"))
        check(request.method() == "POST") { "Uventet kall til sp-rettighetskategori: ${request.method()} ${request.uri()}" }
        antallOppslag++
        if (feiler) return MockHttpResponse("""{"detail":"sp-rettighetskategori er nede"}""", 503)
        val fødselsnummer = objectMapper.readTree(request.bodyAsString()).path("fødselsnummer").asString()
        val hovedkategori =
            rettighetskategorier[id]?.takeIf { it.first == fødselsnummer }?.second
                ?: return MockHttpResponse("""{"detail":"Ikke funnet"}""", 404)
        val body =
            mapOf(
                "id" to id.toString(),
                "skjæringstidspunkt" to LocalDate.of(2026, 1, 1).toString(),
                "hovedkategori" to hovedkategori,
                "tilleggskategorier" to emptyList<String>(),
                "kilde" to "AUTOMATISK",
                "vurderingstidspunkt" to Instant.now().toString(),
            )
        return MockHttpResponse(objectMapper.writeValueAsString(body), 200)
    }

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}
