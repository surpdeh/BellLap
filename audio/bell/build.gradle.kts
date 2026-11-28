plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":audio:source"))
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(project(":testing:fixtures"))
}
