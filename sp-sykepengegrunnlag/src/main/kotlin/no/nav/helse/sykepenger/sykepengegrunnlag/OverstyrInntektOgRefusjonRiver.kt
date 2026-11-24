package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode
import java.util.UUID

/** Overstyringen av inntekt og refusjon fra Spesialist. Refusjonen hører ikke til sykepengegrunnlaget og leses ikke. */
internal class OverstyrInntektOgRefusjonRiver(
    rapidsConnection: RapidsConnection,
    private val sykepengegrunnlagsendring: Sykepengegrunnlagsendring,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition { it.requireValue("@event_name", EVENT) }
                validate {
                    it.require("@id") { node -> UUID.fromString(node.asString()) }
                    it.require("fødselsnummer") { node -> Identitetsnummer.fraString(node.asString()) }
                    it.require("skjæringstidspunkt", JsonNode::asLocalDate)
                    it.requireArray("arbeidsgivere") {
                        require("organisasjonsnummer") { node -> Organisasjonsnummer.fraString(node.asString()) }
                        require("månedligInntekt") { node -> require(node.isNumber) }
                    }
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val fødselsnummer = Identitetsnummer.fraString(packet["fødselsnummer"].asString())
        val skjæringstidspunkt = packet["skjæringstidspunkt"].asLocalDate()
        val overstyringId = OverstyringId(UUID.fromString(packet["@id"].asString()))
        val overstyringer =
            packet["arbeidsgivere"].toList().map {
                Inntektsoverstyring(
                    orgnummer = Organisasjonsnummer.fraString(it.path("organisasjonsnummer").asString()),
                    årsinntekt = it.path("månedligInntekt").decimalValue() * Arbeidsgiverinntekt.MÅNEDER_I_ÅRET,
                )
            }

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer.verdi) {
            try {
                sykepengegrunnlagsendring.endre(fødselsnummer, skjæringstidspunkt, Opphav.OVERSTYRING_INNTEKT, context, meterRegistry) { forrige ->
                    forrige.overstyrInntekt(overstyringId, overstyringer)
                }
            } catch (ex: Exception) {
                loggError(
                    "Feil under overstyring av inntekt i sykepengegrunnlaget",
                    ex,
                    "overstyringId" to overstyringId.uuid.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
            }
        }
    }

    private companion object {
        const val EVENT = "overstyr_inntekt_og_refusjon"
    }
}
