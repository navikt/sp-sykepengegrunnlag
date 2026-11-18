package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.result_object.getOrThrow
import com.github.navikt.tbd_libs.spedisjon.HentMeldingResponse
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

internal class Inntektsmeldinger(
    private val spedisjonClient: SpedisjonClient,
) {
    fun hent(internDokumentIder: List<InternDokumentId>): List<Inntektsmelding> {
        val uuider = internDokumentIder.map { it.uuid }
        val meldinger = spedisjonClient.hentMeldinger(uuider).getOrThrow().meldinger
        val hentet = Instant.now()
        val mangler = uuider - meldinger.map { it.internDokumentId }.toSet()
        check(mangler.isEmpty()) { "Spedisjon mangler inntektsmeldinger: $mangler" }
        val etterId = meldinger.associateBy { it.internDokumentId }
        return uuider.map { etterId.getValue(it).inntektsmelding(hentet) }
    }

    private fun HentMeldingResponse.inntektsmelding(hentet: Instant): Inntektsmelding {
        val body = objectMapper.readTree(jsonBody)
        return Inntektsmelding(
            internDokumentId = InternDokumentId(internDokumentId),
            inntektsmeldingId = InntektsmeldingId(UUID.fromString(body.path("inntektsmeldingId").asString())),
            orgnummer = Organisasjonsnummer.fraString(body.path("virksomhetsnummer").asString()),
            beregnetInntektMånedlig = BigDecimal(body.path("beregnetInntekt").asString()),
            mottattDato = LocalDateTime.parse(body.path("mottattDato").asString()).atZone(OSLO).toInstant(),
            hentet = hentet,
        )
    }

    private companion object {
        val objectMapper = jacksonObjectMapper()
        val OSLO: ZoneId = ZoneId.of("Europe/Oslo")
    }
}
