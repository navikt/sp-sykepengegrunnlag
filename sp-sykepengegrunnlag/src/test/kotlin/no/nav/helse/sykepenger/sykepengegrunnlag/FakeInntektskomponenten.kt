package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.mock.MockHttpResponse
import com.github.navikt.tbd_libs.mock.bodyAsString
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.YearMonth

internal class FakeInntektskomponenten {
    private val inntekter = mutableListOf<Inntekt>()
    private var feiler = false
    val kall = mutableListOf<Kall>()

    val client =
        InntektskomponentenClient(
            baseUrl = "http://inntektskomponenten/rs",
            scope = "api://test.team-inntekt.ikomp/.default",
            tokenProvider = FakeTokenProvider,
            httpClient = FakeHttpClient(::hentInntektliste),
        )

    data class Kall(
        val fødselsnummer: String,
        val aktoerType: String,
        val ainntektsfilter: String,
        val formaal: String,
        val maanedFom: YearMonth,
        val maanedTom: YearMonth,
        val authorization: String?,
    )

    private data class Inntekt(
        val fødselsnummer: String,
        val virksomhet: String,
        val virksomhetstype: String,
        val måned: YearMonth,
        val beløp: String,
        val inntektType: String,
        val fordel: String,
        val beskrivelse: String,
    )

    fun feiler() {
        feiler = true
    }

    fun leggTilInntekt(
        fødselsnummer: String,
        orgnummer: String,
        måned: YearMonth,
        beløp: String,
        inntektType: String = "LOENNSINNTEKT",
        fordel: String = "kontantytelse",
        beskrivelse: String = "fastloenn",
        virksomhetstype: String = "ORGANISASJON",
    ) {
        inntekter.add(Inntekt(fødselsnummer, orgnummer, virksomhetstype, måned, beløp, inntektType, fordel, beskrivelse))
    }

    private fun hentInntektliste(request: HttpRequest): HttpResponse<String> {
        check(request.method() == "POST" && request.uri().toString() == "http://inntektskomponenten/rs/api/v1/hentinntektliste") {
            "Uventet kall til inntektskomponenten: ${request.method()} ${request.uri()}"
        }
        val body = objectMapper.readTree(request.bodyAsString())
        val fom = YearMonth.parse(body.path("maanedFom").asString())
        val tom = YearMonth.parse(body.path("maanedTom").asString())
        val fødselsnummer = body.path("ident").path("identifikator").asString()
        kall.add(
            Kall(
                fødselsnummer = fødselsnummer,
                aktoerType = body.path("ident").path("aktoerType").asString(),
                ainntektsfilter = body.path("ainntektsfilter").asString(),
                formaal = body.path("formaal").asString(),
                maanedFom = fom,
                maanedTom = tom,
                authorization = request.headers().firstValue("Authorization").orElse(null),
            ),
        )
        if (feiler) return MockHttpResponse("""{"detail":"inntektskomponenten er nede"}""", 503)
        val måneder =
            inntekter
                .filter { it.fødselsnummer == fødselsnummer && it.måned in fom..tom }
                .groupBy { it.måned }
                .map { (måned, inntekter) ->
                    mapOf(
                        "aarMaaned" to måned.toString(),
                        "arbeidsInntektInformasjon" to mapOf("inntektListe" to inntekter.map { it.tilInntektskomponenten() }),
                    )
                }
        val respons =
            mapOf(
                "ident" to mapOf("identifikator" to fødselsnummer, "aktoerType" to "NATURLIG_IDENT"),
                "arbeidsInntektMaaned" to måneder,
            )
        return MockHttpResponse(objectMapper.writeValueAsString(respons), 200)
    }

    private fun Inntekt.tilInntektskomponenten() =
        mapOf(
            "inntektType" to inntektType,
            "beloep" to beløp.toBigDecimal(),
            "fordel" to fordel,
            "inntektskilde" to "A-ordningen",
            "inntektsperiodetype" to "Maaned",
            "inntektsstatus" to "LoependeInnrapportert",
            "utbetaltIMaaned" to måned.toString(),
            "opplysningspliktig" to mapOf("identifikator" to virksomhet, "aktoerType" to virksomhetstype),
            "virksomhet" to mapOf("identifikator" to virksomhet, "aktoerType" to virksomhetstype),
            "inntektsmottaker" to mapOf("identifikator" to fødselsnummer, "aktoerType" to "NATURLIG_IDENT"),
            "beskrivelse" to beskrivelse,
        )

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}
