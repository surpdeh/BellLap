package net.belllap.testing.fixtures

import java.io.File

/** `./gradlew :testing:fixtures:generateFixtures`: writes every synthetic fixture to testing/fixtures/generated/. */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: "testing/fixtures/generated")
    val fixtures = Fixtures.all()
    FixtureWriter.write(root, fixtures)
    println("Wrote ${fixtures.size} fixtures to ${root.absolutePath}")
}
