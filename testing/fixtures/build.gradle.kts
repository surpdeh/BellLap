plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":audio:source"))
    api(project(":vision:core"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("generateFixtures") {
    group = "verification"
    description = "Writes the synthetic fixtures (WAV, PNG sequences, labels.json) to testing/fixtures/generated/."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.belllap.testing.fixtures.GenerateFixturesKt")
    args(layout.projectDirectory.dir("generated").asFile.absolutePath)
}
