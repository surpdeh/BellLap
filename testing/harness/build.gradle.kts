plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":testing:fixtures"))
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
}
