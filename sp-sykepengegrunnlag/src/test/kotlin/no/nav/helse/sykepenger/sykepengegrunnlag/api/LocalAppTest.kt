package no.nav.helse.sykepenger.sykepengegrunnlag.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.sykepengegrunnlag.Identitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

internal class LocalAppTest {
    @Test
    fun `tilbyr et lokalt token for Speils proxy`() =
        testApplication {
            application { installerLokaltTokenEndepunkt() }

            val response = client.get("/local-token")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("lokalt-token", response.bodyAsText())
        }

    @Test
    fun `eksempelgrunnlagene gjelder skjæringstidspunktet det spørres om`() {
        val skjæringstidspunkt = LocalDate.of(2026, 3, 1)

        val grunnlag = eksempelhistorikk.finnAlle(Identitetsnummer.fraString(lagIdentitetsnummer()), skjæringstidspunkt)

        assertEquals(listOf(skjæringstidspunkt, skjæringstidspunkt), grunnlag.map { it.skjæringstidspunkt })
    }
}
