plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.belllap.core.log"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    api(libs.timber)

    testImplementation(libs.junit)
}
