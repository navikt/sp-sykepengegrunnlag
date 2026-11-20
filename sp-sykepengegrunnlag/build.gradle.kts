plugins {
    id("no.nav.sykepenger.deployable")
}

sykepengerDeployable {
    mainClass = "no.nav.helse.sykepenger.sykepengegrunnlag.AppKt"
}

dependencies {
    implementation(libs.kotliquery)
    implementation(libs.cloud.sql.postgres.socket.factory)
    implementation(libs.rapids.and.rivers)
    implementation(libs.sykepengerLibs.logging)
    implementation(libs.sykepengerLibs.postgres)
    implementation(libs.tbd.libs.azure.token.client.default)
    implementation(libs.tbd.libs.spedisjon.client)
    implementation(libs.sykepengerLibs.ktor.auth)
    implementation(libs.sykepengerLibs.ktor.openapi)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.jackson3)
    implementation(libs.tbd.libs.access.token.provider.texas)
    implementation(libs.tbd.libs.person.pseudo.id)
    implementation(libs.tbd.libs.populasjonstilgangskontroll.provider.api)
    implementation(libs.tbd.libs.populasjonstilgangskontroll.provider.tilgangsmaskinen)
    implementation(project(":migreringer"))

    testImplementation(libs.tbd.libs.mock.http.client)
    testImplementation(libs.tbd.libs.rapids.and.rivers.test)
    testImplementation(libs.sykepengerLibs.kafka.test)
    testImplementation(testFixtures(libs.sykepengerLibs.postgres))
    testImplementation(libs.sykepengerLibs.testing)
    testImplementation(testFixtures(libs.sykepengerLibs.ktor.openapi))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.cio)
}
