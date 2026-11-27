plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:statemachine"))
    api(project(":audio:source"))
    api(project(":vision:core"))
    testImplementation(libs.junit)
}
