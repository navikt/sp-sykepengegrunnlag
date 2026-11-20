package no.nav.helse.sykepenger.sykepengegrunnlag.api

import com.github.navikt.tbd_libs.access_token.AccessTokenException
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import no.nav.helse.sykepenger.sykepengegrunnlag.Identitetsnummer
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.logging.navngittLogger
import tools.jackson.core.JacksonException
import java.io.IOException

private val logger = navngittLogger("no.nav.helse.sykepenger.sykepengegrunnlag.api")

/** Svarer med feil og gir `null` når saksbehandleren mangler [tilgang] eller tilgang til personen. */
internal suspend fun ApplicationCall.fødselsnummerMedTilgang(
    tilgang: Tilgang,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
): Identitetsnummer? {
    val principal = principal<SaksbehandlerPrincipal>() ?: return null.also { respondIkkeInnlogget() }
    if (!principal.harTilgang(tilgang)) {
        logger.debug("403: saksbehandler mangler tilgang", "tilgang" to tilgang.name)
        return null.also { respondManglerTilgang("Saksbehandleren mangler lesetilgang") }
    }
    val pseudoId =
        parameters["personPseudoId"]?.let(::personPseudoIdOrNull)
            ?: return null.also { respondPersonIkkeFunnet() }
    val ident =
        personProvider.finnIdentitetsnummer(pseudoId)
            ?: return null.also { respondPersonIkkeFunnet() }
    val resultat =
        try {
            tilgangskontroll.kontrollerKjerneTilgang(principal.accessToken.value, ident.value)
        } catch (ex: IOException) {
            return null.also { respondTilgangsfeil(ex) }
        } catch (ex: AccessTokenException) {
            return null.also { respondTilgangsfeil(ex) }
        } catch (ex: JacksonException) {
            return null.also { respondTilgangsfeil(ex) }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return null.also { respondTilgangsfeil(ex) }
        }
    return when (resultat) {
        TilgangskontrollResultat.Ok -> Identitetsnummer.fraString(ident.value)
        TilgangskontrollResultat.IdentIkkeFunnet -> null.also { respondPersonIkkeFunnet() }
        is TilgangskontrollResultat.ManglerTilgang -> {
            logger.debug("403: populasjonstilgangskontrollen ga avslag", "tilgangSomMangler" to resultat.tilgangSomMangler.name)
            null.also { respondManglerTilgangTilPersonen() }
        }
        is TilgangskontrollResultat.UventetFeil -> {
            logger.debug("403: tilgangskontrollen feilet uventet", "forklaring" to resultat.menneskeligLesbarForklaring)
            null.also { respondManglerTilgangTilPersonen() }
        }
    }
}

private fun personPseudoIdOrNull(verdi: String): PersonPseudoId? =
    try {
        PersonPseudoId.fraString(verdi)
    } catch (_: IllegalArgumentException) {
        null
    }

private suspend fun ApplicationCall.respondTilgangsfeil(ex: Exception) {
    logger.debug("403: tilgangskontrollen kunne ikke fullføres", ex)
    respondManglerTilgangTilPersonen()
}

internal suspend fun ApplicationCall.respondIkkeInnlogget() = respondProblem(HttpStatusCode.Unauthorized, "Ikke innlogget", "Kallet krever et gyldig token")

private suspend fun ApplicationCall.respondManglerTilgang(detail: String) = respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang", detail)

private suspend fun ApplicationCall.respondManglerTilgangTilPersonen() = respondManglerTilgang("Saksbehandleren har ikke tilgang til personen")

private suspend fun ApplicationCall.respondPersonIkkeFunnet() = respondProblem(HttpStatusCode.NotFound, "Ikke funnet", "Fant ikke personen")
