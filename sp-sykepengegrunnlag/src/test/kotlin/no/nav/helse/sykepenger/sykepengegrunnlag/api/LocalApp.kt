package no.nav.helse.sykepenger.sykepengegrunnlag.api

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import no.nav.helse.sykepenger.sykepengegrunnlag.Arbeidsgiverinntekt
import no.nav.helse.sykepenger.sykepengegrunnlag.Inntektsmelding
import no.nav.helse.sykepenger.sykepengegrunnlag.InntektsmeldingId
import no.nav.helse.sykepenger.sykepengegrunnlag.InternDokumentId
import no.nav.helse.sykepenger.sykepengegrunnlag.Organisasjonsnummer
import no.nav.helse.sykepenger.sykepengegrunnlag.RettighetskategoriId
import no.nav.helse.sykepenger.sykepengegrunnlag.Skjønnsfastsettelse
import no.nav.helse.sykepenger.sykepengegrunnlag.SkjønnsfastsettelseId
import no.nav.helse.sykepenger.sykepengegrunnlag.Sykepengegrunnlag
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_AUTHENTICATION_NAME
import no.nav.sykepenger.libs.ktor.auth.AccessToken
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.Saksbehandler
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerOid
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PORT = 8686
private const val LOKALT_TOKEN = "lokalt-token"

fun main() {
    val server =
        embeddedServer(CIO, port = PORT) {
            installerLokalAutentisering()
            installerLokaltTokenEndepunkt()
            configureRestApi(eksempelhistorikk, LokalPersonPseudoIdProvider(), TillatAltTilgangskontroll())
        }

    server.start(wait = false)
    println("LocalApp startet. Alle personer og skjæringstidspunkt får de samme eksempelgrunnlagene.")
    println("OpenAPI: http://localhost:$PORT/api/openapi.json")
    println("Swagger UI: http://localhost:$PORT/api/swagger")
    Thread.currentThread().join()
}

internal val eksempelhistorikk =
    Sykepengegrunnlagshistorikk { fødselsnummer, skjæringstidspunkt ->
        val orgnummer = Organisasjonsnummer.fraString("947064649")
        val inntektsmelding =
            Inntektsmelding(
                internDokumentId = InternDokumentId(UUID.randomUUID()),
                inntektsmeldingId = InntektsmeldingId(UUID.randomUUID()),
                orgnummer = orgnummer,
                beregnetInntektMånedlig = BigDecimal("30000"),
                mottattDato = Instant.now(),
                hentet = Instant.now(),
            )
        val vilkårsprøving =
            Sykepengegrunnlag.fastsett(
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                rettighetskategoriId = RettighetskategoriId(UUID.randomUUID()),
                arbeidsgivere = listOf(Arbeidsgiverinntekt.fraInntektsmelding(inntektsmelding)),
                nå = Instant.now(),
            )
        val skjønnsfastsatt =
            vilkårsprøving.skjønnsfastsett(
                SkjønnsfastsettelseId(UUID.randomUUID()),
                listOf(Skjønnsfastsettelse(orgnummer, BigDecimal("400000"))),
            )
        listOf(skjønnsfastsatt, vilkårsprøving)
    }

// Lokalt finnes det ingen Azure AD, så alle slippes inn som en fiktiv saksbehandler med alle tilganger.
private fun Application.installerLokalAutentisering() {
    val principal =
        SaksbehandlerPrincipal(
            saksbehandler =
                Saksbehandler(
                    navIdent = NavIdent("Z999999"),
                    oid = SaksbehandlerOid(UUID.nameUUIDFromBytes("lokal-saksbehandler".toByteArray()).toString()),
                    navn = "Lokal Saksbehandler",
                ),
            tilganger = Tilgang.entries.toSet(),
            accessToken = AccessToken(LOKALT_TOKEN),
        )

    install(Authentication) {
        provider(AZURE_AD_AUTHENTICATION_NAME) {
            authenticate { context -> context.principal(principal) }
        }
    }
}

internal fun Application.installerLokaltTokenEndepunkt() {
    routing {
        get("/local-token") { call.respondText(LOKALT_TOKEN) }
    }
}

// Ukjente pseudo-ID-er blir fiktive personer, så Speil kan bruke hvilken som helst person.
private class LokalPersonPseudoIdProvider : PersonPseudoIdProvider {
    private val personer = ConcurrentHashMap<PersonPseudoId, Identitetsnummer>()

    override fun nyPersonPseudoId(identitetsnummer: Identitetsnummer): PersonPseudoId = PersonPseudoId(UUID.randomUUID()).also { personer[it] = identitetsnummer }

    override fun finnIdentitetsnummer(personPseudoId: PersonPseudoId): Identitetsnummer = personer.getOrPut(personPseudoId) { Identitetsnummer(lagIdentitetsnummer()) }
}

private class TillatAltTilgangskontroll : PopulasjonstilgangskontrollProvider {
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
