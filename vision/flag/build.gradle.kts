plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":vision:core"))
    testImplementation(libs.junit)
    testImplementation(project(":testing:fixtures"))
}
