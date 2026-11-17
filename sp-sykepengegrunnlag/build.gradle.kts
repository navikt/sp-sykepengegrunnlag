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
    implementation(project(":migreringer"))

    testImplementation(libs.tbd.libs.rapids.and.rivers.test)
    testImplementation(libs.sykepengerLibs.kafka.test)
    testImplementation(testFixtures(libs.sykepengerLibs.postgres))
}
