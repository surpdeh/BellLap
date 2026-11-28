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
        // F41: release.yml passes both from the tag and run number; local builds keep the defaults.
        versionCode = providers.gradleProperty("belllap.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("belllap.versionName").getOrElse("0.1.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // F22: where bug reports go. Empty (the default) disables Send; set both in ~/.gradle/gradle.properties.
        buildConfigField("String", "REPORTS_URL", "\"${providers.gradleProperty("belllap.reportsUrl").getOrElse("")}\"")
        buildConfigField(
            "String",
            "FIREBASE_API_KEY",
            "\"${providers.gradleProperty("belllap.firebaseApiKey").getOrElse("")}\"",
        )
    }

    // F41: the release keystore comes from the environment (release.yml decodes it from secrets).
    // Without BELLLAP_KEYSTORE_FILE the release build stays unsigned, so local and PR builds need no secrets.
    val releaseKeystore = providers.environmentVariable("BELLLAP_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("BELLLAP_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("BELLLAP_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("BELLLAP_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
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
    implementation(project(":bugreport"))
    implementation(project(":core:service"))
    implementation(project(":vision:finishline"))
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(project(":core:replay"))
    debugImplementation(project(":vision:flag"))
    debugImplementation(project(":testing:fixtures"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testing:fixtures"))
    testImplementation(project(":testing:fakebackend"))
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
    androidTestImplementation(project(":testing:fakebackend"))
    fixtureTool(project(":testing:fixtures"))
}
