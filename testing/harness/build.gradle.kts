plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":testing:fixtures"))
    implementation(project(":audio:bell"))
    implementation(project(":vision:flag"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

val reportsDir = layout.buildDirectory.dir("reports")

tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replays fixtures through detectors, writes build/reports/metrics.json, checks thresholds.json."
    dependsOn(tasks.named("test"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.belllap.testing.harness.ReplayKt")
    val thresholds = rootProject.file("testing/thresholds.json")
    inputs.file(thresholds)
    outputs.dir(reportsDir)
    args(reportsDir.get().asFile.absolutePath, thresholds.absolutePath)
    // Stage 1 for the bell cascade: off by default; see scripts/bell/replay-yamnet.sh for the YAMNet passes.
    listOf("bell.dump", "bell.scores", "bell.ambienceHours").forEach { key ->
        providers.gradleProperty(key).orNull?.let {
            systemProperty("belllap.$key", it)
            inputs.property(key, it)
        }
    }
    providers.gradleProperty("bell.scores").orNull?.let { inputs.file(it) }
}

tasks.register<JavaExec>("replayReal") {
    group = "verification"
    description = "Replays real footage fetched by scripts/fixtures/fetch.sh (report only). Skips cleanly if absent."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.belllap.testing.harness.ReplayReal")
    val root = rootProject.file("testing/fixtures/real")
    outputs.upToDateWhen { false } // fixtures live outside the build; always re-run
    outputs.dir(reportsDir)
    args(reportsDir.get().asFile.absolutePath, root.absolutePath)
    listOf("bell.dump", "bell.scores").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty("belllap.$key", it) }
    }
}
