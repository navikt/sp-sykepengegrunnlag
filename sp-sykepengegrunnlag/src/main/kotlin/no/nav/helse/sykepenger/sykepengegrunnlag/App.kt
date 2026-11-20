package no.nav.helse.sykepenger.sykepengegrunnlag

import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import io.ktor.server.application.ApplicationStopped
import no.nav.helse.rapids_rivers.RapidApplication
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
    )
}

internal fun launchApplication(
    env: Map<String, String>,
    kafkaConfig: Config,
    spedisjonClient: SpedisjonClient,
    rettighetskategoriClient: RettighetskategoriClient,
    inntektskomponentenClient: InntektskomponentenClient,
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

internal fun RapidsConnection.registrerRivers(
    dataSource: DataSource,
    spedisjonClient: SpedisjonClient,
    rettighetskategoriClient: RettighetskategoriClient,
    inntektskomponentenClient: InntektskomponentenClient,
) {
    SykepengegrunnlagRiver(this, Inntektsmeldinger(spedisjonClient), Søknader(spedisjonClient), rettighetskategoriClient, inntektskomponentenClient, dataSource)
    SammenligningRiver(this, dataSource)
    SkjønnsmessigFastsettelseRiver(this, Sykepengegrunnlagsendring(dataSource))
}
