package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.result_object.getOrThrow
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

internal class Søknader(
    private val spedisjonClient: SpedisjonClient,
) {
    fun hent(internDokumentId: InternDokumentId): Søknad {
        val melding =
            spedisjonClient
                .hentMeldinger(listOf(internDokumentId.uuid))
                .getOrThrow()
                .meldinger
                .singleOrNull { it.internDokumentId == internDokumentId.uuid }
        checkNotNull(melding) { "Spedisjon mangler søknaden ${internDokumentId.uuid}" }
        val hentet = Instant.now()
        val body = objectMapper.readTree(melding.jsonBody)
        return Søknad(
            internDokumentId = internDokumentId,
            søknadId = SøknadId(UUID.fromString(body.path("id").asString())),
            pensjonsgivendeInntekter =
                body
                    .path("selvstendigNaringsdrivende")
                    .path("inntekt")
                    .path("inntektsAar")
                    .toList()
                    .map { it.pensjonsgivendeInntekt() },
            hentet = hentet,
        )
    }

    // Leses som i SendtSøknadSelvstendigMessage i Spleis: manglende beløp blir 0, og manglende erFerdigLignet blir true.
    private fun JsonNode.pensjonsgivendeInntekt(): PensjonsgivendeInntekt {
        val inntekt = path("pensjonsgivendeInntekt")
        return PensjonsgivendeInntekt(
            år = path("aar").asString().toInt(),
            erFerdigLignet = path("erFerdigLignet").asBoolean(true),
            lønnsinntekt = inntekt.path("pensjonsgivendeInntektAvLoennsinntekt").asInt(0),
            lønnsinntektBarePensjonsdel = inntekt.path("pensjonsgivendeInntektAvLoennsinntektBarePensjonsdel").asInt(0),
            næringsinntekt = inntekt.path("pensjonsgivendeInntektAvNaeringsinntekt").asInt(0),
            næringsinntektFraFiskeFangstEllerFamiliebarnehage = inntekt.path("pensjonsgivendeInntektAvNaeringsinntektFraFiskeFangstEllerFamiliebarnehage").asInt(0),
        )
    }

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}
