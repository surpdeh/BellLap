import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.belllap.audio.android"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The model is memory-mapped straight out of the APK, which needs it stored uncompressed.
    androidResources {
        noCompress += "tflite"
    }
}

/**
 * Fetches YAMNet (Apache-2.0) at build time and checks its SHA-256, so the 4 MB model never lands in git.
 * The label list is packed inside the .tflite as a zip; it is extracted next to the model.
 */
abstract class FetchYamnet : DefaultTask() {
    @get:Input abstract val url: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        val model = File(dir, "yamnet.tflite")
        if (!model.isFile || digest(model) != sha256.get()) {
            URI(url.get()).toURL().openStream().use { input -> model.outputStream().use { input.copyTo(it) } }
        }
        val actual = digest(model)
        check(actual == sha256.get()) { "yamnet.tflite SHA-256 is $actual, expected ${sha256.get()}" }
        ZipFile(model).use { zip ->
            val entry = requireNotNull(zip.getEntry("yamnet_label_list.txt")) { "no label list inside the model" }
            File(dir, "yamnet_labels.txt").writeBytes(zip.getInputStream(entry).readBytes())
        }
    }

    private fun digest(f: File) =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}

val fetchYamnet = tasks.register<FetchYamnet>("fetchYamnet") {
    url.set(providers.gradleProperty("belllap.yamnet.url"))
    sha256.set(providers.gradleProperty("belllap.yamnet.sha256"))
    outputDir.set(layout.buildDirectory.dir("generated/yamnet"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchYamnet, FetchYamnet::outputDir)
    }
}

dependencies {
    api(project(":audio:bell"))
    implementation(libs.litert)
    implementation(libs.timber)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(project(":testing:fixtures"))
}
