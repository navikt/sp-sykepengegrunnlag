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
import java.time.Instant

/**
 * Grunnbeløpsreguleringen for ett skjæringstidspunkt. spregulering sender den for hvert grunnlag som er fastsatt med feil grunnbeløp,
 * både automatisk og når den startes med `kjør_grunnbeløpsregulering` fra Spout. Den kan også sendes for én person fra Spout.
 */
internal class GrunnbeløpsreguleringRiver(
    rapidsConnection: RapidsConnection,
    private val sykepengegrunnlagsendring: Sykepengegrunnlagsendring,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition { it.requireValue("@event_name", EVENT) }
                validate {
                    it.require("fødselsnummer") { node -> Identitetsnummer.fraString(node.asString()) }
                    it.require("skjæringstidspunkt", JsonNode::asLocalDate)
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

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer.verdi) {
            try {
                sykepengegrunnlagsendring.endre(fødselsnummer, skjæringstidspunkt, Opphav.GRUNNBELØPSREGULERING, context, meterRegistry) { forrige ->
                    forrige.grunnbeløpsreguler(nå = Instant.now())
                }
            } catch (ex: Exception) {
                loggError(
                    "Feil under grunnbeløpsregulering av sykepengegrunnlaget",
                    ex,
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
            }
        }
    }

    private companion object {
        const val EVENT = "grunnbeløpsregulering"
    }
}
