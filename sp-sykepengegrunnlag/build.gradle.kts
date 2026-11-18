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
    implementation(project(":migreringer"))

    testImplementation(libs.tbd.libs.mock.http.client)
    testImplementation(libs.tbd.libs.rapids.and.rivers.test)
    testImplementation(libs.sykepengerLibs.kafka.test)
    testImplementation(testFixtures(libs.sykepengerLibs.postgres))
    testImplementation(libs.sykepengerLibs.testing)
}
