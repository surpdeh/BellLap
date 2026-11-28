plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.belllap.core.service"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:statemachine"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
