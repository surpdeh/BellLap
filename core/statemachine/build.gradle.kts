plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    System.getProperty("belllap.updateDocs")?.let { systemProperty("belllap.updateDocs", it) }
}
