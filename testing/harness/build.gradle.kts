plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.register("replay") {
    group = "verification"
    description = "Replays fixtures through detectors; metrics output arrives with F03."
    dependsOn(tasks.named("test"))
}
