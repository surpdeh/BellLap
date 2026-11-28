package net.belllap.bugreport

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Fails when [Caps] drifts from the Worker's `caps.ts`, the source of truth for what the backend accepts. */
class CapsDriftTest {
    private val capsTs = File("../backend/worker/src/caps.ts")

    private fun source(): String {
        assumeTrue(
            "backend/worker/src/caps.ts not found from ${File(".").absoluteFile}; drift check skipped",
            capsTs.isFile,
        )
        return capsTs.readText()
    }

    private fun bytes(text: String): Long {
        val m = Regex("""(\d+)\s*\*\s*MiB""").matchEntire(text.trim())
        return if (m != null) m.groupValues[1].toLong() * MIB else text.trim().toLong()
    }

    @Test
    fun globalLimitsMatch() {
        val ts = source()

        fun const(name: String) = Regex("""export const $name = ([^;]+);""").find(ts)!!.groupValues[1]
        assertEquals(Caps.MAX_ATTACHMENTS.toLong(), bytes(const("MAX_ATTACHMENTS")))
        assertEquals(Caps.MAX_DESCRIPTION.toLong(), bytes(const("MAX_DESCRIPTION")))
        assertEquals(Caps.MAX_TOTAL_BYTES, bytes(const("MAX_TOTAL_BYTES")))
    }

    @Test
    fun perKindCapsMatch() {
        val ts = source()
        val block = ts.substringAfter("export const ATTACHMENT_KINDS = {").substringBefore("} as const")
        val kinds = Regex("""(\w+): \{\s*maxBytes: ([^,]+),\s*maxCount: (\d+),\s*contentTypes: \[([^\]]*)]""")
            .findAll(block).toList()
        assertEquals(
            "kinds parsed from caps.ts",
            AttachmentKind.entries.map {
                it.wire
            }.toSet(),
            kinds.map { it.groupValues[1] }.toSet(),
        )
        kinds.forEach { m ->
            val kind = AttachmentKind.entries.first { it.wire == m.groupValues[1] }
            val caps = Caps.of(kind)
            assertEquals("$kind maxBytes", bytes(m.groupValues[2]), caps.maxBytes)
            assertEquals("$kind maxCount", m.groupValues[3].toInt(), caps.maxCount)
            val types = Regex("'([^']+)'").findAll(m.groupValues[4]).map { it.groupValues[1] }.toSet()
            assertEquals("$kind contentTypes", types, caps.contentTypes)
        }
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
