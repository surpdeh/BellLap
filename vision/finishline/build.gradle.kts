plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    api(project(":vision:core"))
    testImplementation(project(":testing:fixtures"))
    testImplementation(libs.junit)
}
