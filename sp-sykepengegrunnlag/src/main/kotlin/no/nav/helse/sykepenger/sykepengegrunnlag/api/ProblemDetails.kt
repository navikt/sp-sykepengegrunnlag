package no.nav.helse.sykepenger.sykepengegrunnlag.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import tools.jackson.module.kotlin.jacksonObjectMapper

internal data class ApiProblemDetails(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
)

internal suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    detail: String,
) {
    respondText(
        problemMapper.writeValueAsString(ApiProblemDetails(title = title, status = status.value, detail = detail, instance = request.path())),
        ContentType.Application.ProblemJson,
        status,
    )
}

private val problemMapper = jacksonObjectMapper()
