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

/** Skjønnsfastsettelsen fra Spesialist. Avviksvurderingen og skjønnslogikken blir i Spinnvill og Spesialist. */
internal class SkjønnsmessigFastsettelseRiver(
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
                        require("årlig") { node -> require(node.isNumber) }
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
        val skjønnsfastsettelseId = SkjønnsfastsettelseId(UUID.fromString(packet["@id"].asString()))
        val fastsettelser =
            packet["arbeidsgivere"].toList().map {
                Skjønnsfastsettelse(
                    orgnummer = Organisasjonsnummer.fraString(it.path("organisasjonsnummer").asString()),
                    årsinntekt = it.path("årlig").decimalValue(),
                )
            }

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer.verdi) {
            try {
                sykepengegrunnlagsendring.endre(fødselsnummer, skjæringstidspunkt, Opphav.SKJØNNSFASTSETTELSE, context, meterRegistry) { forrige ->
                    forrige.skjønnsfastsett(skjønnsfastsettelseId, fastsettelser)
                }
            } catch (ex: Exception) {
                loggError(
                    "Feil under skjønnsfastsettelse av sykepengegrunnlaget",
                    ex,
                    "skjønnsfastsettelseId" to skjønnsfastsettelseId.uuid.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
            }
        }
    }

    private companion object {
        const val EVENT = "skjønnsmessig_fastsettelse"
    }
}
