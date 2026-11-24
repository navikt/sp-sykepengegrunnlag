package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.access_token.TexasClient
import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.personpseudoid.ValkeyPersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import com.github.navikt.tbd_libs.populasjonstilgang.client.PopulasjonstilgangConfig
import com.github.navikt.tbd_libs.populasjonstilgang.client.tilgangsmaskinenClient
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import io.ktor.server.application.ApplicationStopped
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.helse.sykepenger.sykepengegrunnlag.api.PgSykepengegrunnlagshistorikk
import no.nav.helse.sykepenger.sykepengegrunnlag.api.configureRestApi
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.navngittLogger
import no.nav.sykepenger.libs.postgres.DatabaseConfig
import no.nav.sykepenger.libs.postgres.dataSource
import no.nav.sykepenger.libs.postgres.migrerSynkront
import tools.jackson.module.kotlin.jacksonObjectMapper
import javax.sql.DataSource

private val logger = navngittLogger("no.nav.helse.sykepenger.sykepengegrunnlag.App")

fun main() {
    val env = System.getenv()
    val tokenProvider = createAzureTokenClientFromEnvironment(env)
    launchApplication(
        env = env,
        kafkaConfig = AivenConfig.default,
        spedisjonClient =
            SpedisjonClient(
                objectMapper = jacksonObjectMapper(),
                tokenProvider = tokenProvider,
                baseUrl = env.getValue("SPEDISJON_BASE_URL"),
                scope = env.getValue("SPEDISJON_SCOPE"),
            ),
        rettighetskategoriClient = RettighetskategoriClient.fraMiljø(env, tokenProvider),
        inntektskomponentenClient = InntektskomponentenClient.fraMiljø(env, tokenProvider),
        personProvider = ValkeyPersonPseudoIdProvider.fraEnv("personpseudoid", env),
        tilgangskontroll = tilgangskontrollFraEnv(env),
    )
}

// Appen har ennå ikke fått tilgang til tilgangsmaskinen i dev, så der slipper vi alle gjennom inntil videre (navikt/sas-issues#392).
private fun tilgangskontrollFraEnv(env: Map<String, String>): PopulasjonstilgangskontrollProvider =
    if (env["NAIS_CLUSTER_NAME"] == "dev-gcp") {
        TillatAltTilgangskontroll
    } else {
        PopulasjonstilgangConfig.fraEnv(env).tilgangsmaskinenClient(TexasClient.fromEnv())
    }

private object TillatAltTilgangskontroll : PopulasjonstilgangskontrollProvider {
    override fun kontrollerKomplettTilgang(
        accessToken: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok

    override fun kontrollerKjerneTilgang(
        accessToken: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok

    override fun kontrollerKjerneTilgangForAnsatt(
        ansattId: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok
}

internal fun launchApplication(
    env: Map<String, String>,
    kafkaConfig: Config,
    spedisjonClient: SpedisjonClient,
    rettighetskategoriClient: RettighetskategoriClient,
    inntektskomponentenClient: InntektskomponentenClient,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
) {
    val databaseConfig = DatabaseConfig.fraEnv(env)
    logger.info("Migrerer database")
    migrerSynkront(databaseConfig)
    logger.info("Migrering ferdig")
    val dataSource = databaseConfig.dataSource()

    RapidApplication
        .create(
            env = env,
            consumerProducerFactory = ConsumerProducerFactory(kafkaConfig),
            builder = {
                env["HTTP_PORT"]?.toInt()?.let(::withHttpPort)
                withKtorModule {
                    configureJwtAuthentication(AzureAdConfig.fraEnv(env), lesetilgangFraEnv(env))
                    configureRestApi(PgSykepengegrunnlagshistorikk(dataSource), personProvider, tilgangskontroll)
                    monitor.subscribe(ApplicationStopped) {
                        loggInfo("Lukker datasource")
                        dataSource.close()
                    }
                }
            },
        ).apply {
            registrerRivers(dataSource, spedisjonClient, rettighetskategoriClient, inntektskomponentenClient)
        }.start()
}

// API-et har bare lesing, så appen har ingen skrivegruppe (TILGANG_SKRIV).
private fun lesetilgangFraEnv(env: Map<String, String>) =
    TilgangsgrupperTilTilganger(
        tilgangLesGruppeIder =
            env
                .getValue("TILGANG_LES")
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet(),
        tilgangSkrivGruppeIder = emptySet(),
    )

internal fun RapidsConnection.registrerRivers(
    dataSource: DataSource,
    spedisjonClient: SpedisjonClient,
    rettighetskategoriClient: RettighetskategoriClient,
    inntektskomponentenClient: InntektskomponentenClient,
) {
    SykepengegrunnlagRiver(this, Inntektsmeldinger(spedisjonClient), Søknader(spedisjonClient), rettighetskategoriClient, inntektskomponentenClient, dataSource)
    SammenligningRiver(this, dataSource)
    SkjønnsmessigFastsettelseRiver(this, Sykepengegrunnlagsendring(dataSource))
    OverstyrInntektOgRefusjonRiver(this, Sykepengegrunnlagsendring(dataSource))
}
