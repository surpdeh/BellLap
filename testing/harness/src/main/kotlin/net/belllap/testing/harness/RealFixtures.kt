package net.belllap.testing.harness

import net.belllap.testing.fixtures.Labels
import java.io.File

/**
 * Real race footage (F03b) lives outside git, in a private bucket, and is fetched by `scripts/fixtures/fetch.sh`
 * into [DEFAULT_DIR]. Each fixture is a directory holding `labels.json` plus the media it names.
 * When none are present the harness skips cleanly instead of failing.
 */
object RealFixtures {
    const val ENV_DIR = "BELLLAP_REAL_FIXTURES"
    const val DEFAULT_DIR = "testing/fixtures/real"

    /** Names of the fixture directories under [root] that carry a `labels.json`, sorted. Empty if [root] is absent. */
    fun discover(root: File): List<String> =
        root.listFiles { f -> f.isDirectory && File(f, Labels.FILE_NAME).isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun resolveRoot(env: String? = System.getenv(ENV_DIR), default: File): File = File(env ?: default.path)
}
