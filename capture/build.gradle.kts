plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.belllap.capture"
}

dependencies {
    api(project(":core:model"))
    api(project(":vision:core"))
    api(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
}
