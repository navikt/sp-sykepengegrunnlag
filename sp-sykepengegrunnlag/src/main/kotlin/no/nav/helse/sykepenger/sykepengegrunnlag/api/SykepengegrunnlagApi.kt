package no.nav.helse.sykepenger.sykepengegrunnlag.api

import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.serialization.jackson3.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import kotliquery.sessionOf
import no.nav.helse.sykepenger.sykepengegrunnlag.Identitetsnummer
import no.nav.helse.sykepenger.sykepengegrunnlag.PgSykepengegrunnlagRepository
import no.nav.helse.sykepenger.sykepengegrunnlag.Sykepengegrunnlag
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_AUTHENTICATION_NAME
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.ktor.openapi.configureOpenApi
import no.nav.sykepenger.libs.logging.navngittLogger
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.sql.DataSource

private val logger = navngittLogger("no.nav.helse.sykepenger.sykepengegrunnlag.api")

internal fun interface Sykepengegrunnlagshistorikk {
    fun finnAlle(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
    ): List<Sykepengegrunnlag>
}

internal class PgSykepengegrunnlagshistorikk(
    private val dataSource: DataSource,
) : Sykepengegrunnlagshistorikk {
    override fun finnAlle(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
    ) = sessionOf(dataSource).use { session ->
        session.transaction { PgSykepengegrunnlagRepository(it).finnAlle(fødselsnummer, skjæringstidspunkt) }
    }
}

internal fun Application.configureRestApi(
    historikk: Sykepengegrunnlagshistorikk,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
) {
    install(ContentNegotiation) { jackson() }
    install(StatusPages) {
        status(HttpStatusCode.Unauthorized) { call, _ -> call.respondIkkeInnlogget() }
        exception<Throwable> { call, cause ->
            logger.error("Uventet feil ved API-kall", cause)
            call.respondProblem(HttpStatusCode.InternalServerError, "Intern serverfeil", "Noe gikk galt. Prøv igjen senere.")
        }
    }
    configureOpenApi(tittel = "sp-sykepengegrunnlag", versjon = "1")
    routing {
        authenticate(AZURE_AD_AUTHENTICATION_NAME) {
            sykepengegrunnlagApi(historikk, personProvider, tilgangskontroll)
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.sykepengegrunnlagApi(
    historikk: Sykepengegrunnlagshistorikk,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
) {
    get("/api/personer/{personPseudoId}/sykepengegrunnlag") {
        val fødselsnummer = call.fødselsnummerMedTilgang(Tilgang.Les, personProvider, tilgangskontroll) ?: return@get
        val skjæringstidspunkt =
            call.request.queryParameters["skjaeringstidspunkt"]?.let(::datoOrNull)
                ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel", "Mangler gyldig skjæringstidspunkt på formen YYYY-MM-DD")
        call.respond(historikk.finnAlle(fødselsnummer, skjæringstidspunkt).tilApi())
    }.describe {
        tag("sykepengegrunnlag")
        operationId = "hentSykepengegrunnlag"
        summary = "Henter alle sykepengegrunnlagene for et skjæringstidspunkt"
        description = "Grunnlagene kommer med det nyeste først. Hver endring gir et nytt grunnlag, og de gamle beholdes."
        parameters {
            path("personPseudoId") { schema = jsonSchema<java.util.UUID>() }
            query("skjaeringstidspunkt") {
                required = true
                schema = jsonSchema<LocalDate>()
            }
        }
        responses {
            HttpStatusCode.OK { schema = jsonSchema<ApiSykepengegrunnlagListe>() }
            listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound).forEach { status ->
                status { ContentType.Application.ProblemJson { schema = jsonSchema<ApiProblemDetails>() } }
            }
        }
    }
}

private fun datoOrNull(verdi: String): LocalDate? =
    try {
        LocalDate.parse(verdi)
    } catch (_: DateTimeParseException) {
        null
    }
