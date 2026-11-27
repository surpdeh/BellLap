import com.android.build.api.dsl.CommonExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Toolchain JDK (compiler) vs bytecode level. The toolchain can track the latest LTS; the bytecode stays at 21
// because D8 desugars it for minSdk 29 devices, and pure-JVM modules end up in the APK too.
val jdkToolchain = 25
val bytecodeTarget = JavaVersion.VERSION_21

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")

    // Plugin 14.x defaults to a newer ktlint engine whose rule set reformats most files; keep the engine the
    // repo was formatted with (plugin 12.3.0's default) until a dedicated reformat change.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.0.1")
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget.set(bytecodeTarget.toString())
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        compilerOptions.allWarningsAsErrors.set(true)
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(jdkToolchain)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(bytecodeTarget.majorVersion.toInt())
        }
        // Android modules expose testDebugUnitTest; give pure-JVM modules the same name so one command runs every unit test.
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Alias of test, so ./gradlew testDebugUnitTest covers JVM and Android modules."
            dependsOn(tasks.named("test"))
        }
    }

    listOf("com.android.application", "com.android.library").forEach { id ->
        plugins.withId(id) { configureAndroid() }
    }
}

fun Project.configureAndroid() {
    extensions.configure<CommonExtension>("android") {
        compileSdk = 36
        defaultConfig.minSdk = 29
        compileOptions.apply {
            sourceCompatibility = bytecodeTarget
            targetCompatibility = bytecodeTarget
        }
        lint.apply {
            warningsAsErrors = true
            abortOnError = true
            // OldTargetApi: targetSdk is pinned to 36 by project rule (CLAUDE.md); raising it changes runtime
            // behaviour on the newest Android release and is its own reviewed change, not a toolchain bump.
            disable += listOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
        }
    }
}
