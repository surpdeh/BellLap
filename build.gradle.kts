import com.android.build.api.dsl.CommonExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

val jdkVersion = 21

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        compilerOptions.allWarningsAsErrors.set(true)
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(jdkVersion)
        }
        // Android modules expose testDebugUnitTest; give pure-JVM modules the same name so one command runs every unit test.
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Alias of test, so ./gradlew testDebugUnitTest covers JVM and Android modules."
            dependsOn(tasks.named("test"))
        }
    }

    plugins.withType<com.android.build.gradle.BasePlugin> {
        (extensions.getByName("android") as CommonExtension<*, *, *, *, *, *>).apply {
            compileSdk = 36
            defaultConfig.minSdk = 29
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
            lint {
                warningsAsErrors = true
                abortOnError = true
                disable += listOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
            }
        }
    }
}
