package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import kotliquery.sessionOf
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc
import java.util.UUID
import javax.sql.DataSource

/** Svarer med grunnlaget for en sykepengegrunnlag-ID. Backfill ved ukjent ID kommer senere, til da svarer ikke tjenesten. */
internal class SykepengegrunnlagResultatRiver(
    rapidsConnection: RapidsConnection,
    private val dataSource: DataSource,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition {
                    it.requireValue("@event_name", "behov")
                    it.requireAllOrAny("@behov", listOf(BEHOV))
                    it.forbid("@løsning")
                }
                validate {
                    it.require("fødselsnummer") { node -> Identitetsnummer.fraString(node.asString()) }
                    it.require(ID_FELT) { node -> UUID.fromString(node.asString()) }
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
        val id = Sykepengegrunnlag.Id(UUID.fromString(packet[ID_FELT].asString()))

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer.verdi) {
            loggInfo("Mottatt behov for $BEHOV", "sykepengegrunnlagId" to id.uuid.toString())
            try {
                val grunnlag =
                    sessionOf(dataSource).use { session ->
                        session.transaction { PgSykepengegrunnlagRepository(it).finnOrNull(id) }
                    }
                if (grunnlag == null) {
                    loggError("Fant ikke sykepengegrunnlaget, svarer ikke på $BEHOV", "sykepengegrunnlagId" to id.uuid.toString())
                    return@medMdc
                }
                packet["@løsning"] = mapOf(BEHOV to SykepengegrunnlagResultat.fra(grunnlag).tilLøsning())
                loggInfo("Publiserer løsning på $BEHOV", "sykepengegrunnlagId" to id.uuid.toString())
                context.publish(packet.toJson())
            } catch (ex: Exception) {
                loggError("Feil under håndtering av behov $BEHOV", ex, "sykepengegrunnlagId" to id.uuid.toString())
            }
        }
    }

    private companion object {
        const val BEHOV = "SykepengegrunnlagResultat"
        const val ID_FELT = "$BEHOV.sykepengegrunnlagId"
    }
}
