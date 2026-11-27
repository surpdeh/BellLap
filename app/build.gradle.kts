plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "net.belllap"

    defaultConfig {
        applicationId = "net.belllap"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

/** Runs [net.belllap.testing.fixtures.RaceSynthMedia] (host ffmpeg); its output becomes androidTest assets. */
@CacheableTask
abstract class RaceSynthMediaTask : JavaExec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val fixtureTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}

val raceSynthMedia = tasks.register<RaceSynthMediaTask>("raceSynthMedia") {
    group = "verification"
    description = "Encodes race_synth to MP4 with host ffmpeg for the emulator replay tests (skipped without ffmpeg)."
    classpath = fixtureTool
    mainClass.set("net.belllap.testing.fixtures.RaceSynthMediaKt")
    outputDir.set(layout.buildDirectory.dir("generated/raceSynthMedia"))
    argumentProviders.add(CommandLineArgumentProvider { listOf(outputDir.get().dir("replay").asFile.absolutePath) })
    val hasFfmpeg = providers.exec {
        commandLine("sh", "-c", "command -v ffmpeg")
        isIgnoreExitValue = true
    }.result.map { it.exitValue == 0 }
    onlyIf("ffmpeg is on PATH; without it the app encodes race_synth on the device") { hasFfmpeg.get() }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(raceSynthMedia, RaceSynthMediaTask::outputDir)
    }
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":core:log"))
    implementation(project(":core:model"))
    implementation(project(":core:statemachine"))
    implementation(project(":audio:android"))
    implementation(project(":capture"))
    implementation(project(":vision:finishline"))
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(project(":core:replay"))
    debugImplementation(project(":testing:fixtures"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testing:fixtures"))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(project(":core:log"))
    fixtureTool(project(":testing:fixtures"))
}
