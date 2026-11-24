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

/** Overstyringen av arbeidsforhold fra Spesialist, der saksbehandler deaktiverer eller aktiverer arbeidsforhold i grunnlaget. */
internal class OverstyrArbeidsforholdRiver(
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
                    it.requireArray("overstyrteArbeidsforhold") {
                        require("orgnummer") { node -> Organisasjonsnummer.fraString(node.asString()) }
                        require("deaktivert") { node -> require(node.isBoolean) }
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
            packet["overstyrteArbeidsforhold"].toList().map {
                Arbeidsforholdoverstyring(
                    orgnummer = Organisasjonsnummer.fraString(it.path("orgnummer").asString()),
                    deaktivert = it.path("deaktivert").booleanValue(),
                )
            }

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer.verdi) {
            try {
                sykepengegrunnlagsendring.endre(fødselsnummer, skjæringstidspunkt, Opphav.OVERSTYRING_ARBEIDSFORHOLD, context, meterRegistry) { forrige ->
                    loggUkjenteArbeidsforhold(forrige, overstyringer, overstyringId)
                    forrige.overstyrArbeidsforhold(overstyringer)
                }
            } catch (ex: Exception) {
                loggError(
                    "Feil under overstyring av arbeidsforhold i sykepengegrunnlaget",
                    ex,
                    "overstyringId" to overstyringId.uuid.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
            }
        }
    }

    /** Spleis feiler på arbeidsforhold som ikke er i grunnlaget. Vi ignorerer dem, men logger det, fordi grunnlagene da er ulike. */
    private fun loggUkjenteArbeidsforhold(
        forrige: Sykepengegrunnlag,
        overstyringer: List<Arbeidsforholdoverstyring>,
        overstyringId: OverstyringId,
    ) {
        val ukjente = forrige.ukjenteArbeidsforhold(overstyringer)
        if (ukjente.isEmpty()) return
        loggError(
            "Overstyringen av arbeidsforhold gjelder arbeidsforhold som ikke er i sykepengegrunnlaget",
            "overstyringId" to overstyringId.uuid.toString(),
            "sykepengegrunnlagId" to forrige.id.uuid.toString(),
            "orgnumre" to ukjente.joinToString { it.verdi },
        )
    }

    private companion object {
        const val EVENT = "overstyr_arbeidsforhold"
    }
}
