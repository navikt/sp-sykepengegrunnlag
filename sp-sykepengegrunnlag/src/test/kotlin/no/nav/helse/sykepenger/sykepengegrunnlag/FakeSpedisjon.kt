package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.mock.MockHttpResponse
import com.github.navikt.tbd_libs.mock.bodyAsString
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.util.UUID

internal class FakeSpedisjon {
    private val meldinger = mutableMapOf<UUID, Map<String, Any>>()
    private var feiler = false

    val client =
        SpedisjonClient(
            httpClient = FakeHttpClient(::hentMeldinger),
            objectMapper = objectMapper,
            tokenProvider = FakeTokenProvider,
            baseUrl = "http://spedisjon",
            scope = "api://test.tbd.spedisjon/.default",
        )

    fun feiler() {
        feiler = true
    }

    fun lagInntektsmelding(
        fødselsnummer: String,
        orgnummer: String,
        beregnetInntekt: String = "30000",
        mottattDato: LocalDateTime = LocalDateTime.of(2026, 1, 20, 12, 0),
    ): UUID {
        val internDokumentId = UUID.randomUUID()
        val eksternDokumentId = UUID.randomUUID()
        val inntektsmelding =
            mapOf(
                "inntektsmeldingId" to eksternDokumentId.toString(),
                "arbeidstakerFnr" to fødselsnummer,
                "virksomhetsnummer" to orgnummer,
                "beregnetInntekt" to beregnetInntekt,
                "mottattDato" to mottattDato.toString(),
                "format" to "Inntektsmelding",
            )
        meldinger[internDokumentId] =
            mapOf(
                "type" to "inntektsmelding",
                "fnr" to fødselsnummer,
                "internDokumentId" to internDokumentId.toString(),
                "eksternDokumentId" to eksternDokumentId.toString(),
                "duplikatkontroll" to UUID.randomUUID().toString(),
                "jsonBody" to objectMapper.writeValueAsString(inntektsmelding),
            )
        return internDokumentId
    }

    private fun hentMeldinger(request: HttpRequest): HttpResponse<String> {
        check(request.method() == "GET" && request.uri().path == "/api/meldinger") { "Uventet kall til Spedisjon: ${request.method()} ${request.uri()}" }
        if (feiler) return MockHttpResponse("""{"detail":"Spedisjon er nede"}""", 503)
        val internDokumentIder =
            objectMapper
                .readTree(request.bodyAsString())
                .path("internDokumentIder")
                .toList()
                .map { UUID.fromString(it.asString()) }
        val body = mapOf("meldinger" to internDokumentIder.mapNotNull { meldinger[it] })
        return MockHttpResponse(objectMapper.writeValueAsString(body), 200)
    }

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}
