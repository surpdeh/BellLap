package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AcquisitionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sidecar(id: String, name: String = "Race $id") = Sidecar(
        id = id,
        name = name,
        videoFile = "acq_$id.mp4",
        device = SidecarDevice("G", "P", 36),
        fps = 30,
        width = 640,
        height = 480,
        firstFramePtsUs = 0,
        appVersion = "t",
    )

    private fun dir() = File(tmp.root, "acquisitions")

    private fun put(id: String, modified: Long, name: String = "Race $id") {
        dir().mkdirs()
        File(dir(), "acq_$id.mp4").writeText("video")
        File(dir(), "acq_$id.json").apply {
            writeText(sidecar(id, name).encode())
            setLastModified(modified)
        }
    }

    @Test
    fun missingDirectoryListsNothing() {
        assertEquals(emptyList<Acquisition>(), AcquisitionStore(dir()).list())
    }

    @Test
    fun listsNewestFirstAndSkipsInvalidSidecars() {
        put("old", 1_000)
        put("new", 3_000)
        put("mid", 2_000)
        File(dir(), "acq_bad.json").writeText("{ nope")
        File(dir(), "acq_bad.mp4").writeText("video")
        assertEquals(listOf("new", "mid", "old"), AcquisitionStore(dir()).list().map { it.sidecar.id })
    }

    @Test
    fun skipsASidecarWhoseVideoIsMissing() {
        put("a", 1_000)
        File(dir(), "acq_a.mp4").delete()
        assertEquals(emptyList<Acquisition>(), AcquisitionStore(dir()).list())
    }

    @Test
    fun renameKeepsEverythingElse() {
        put("a", 1_000)
        val store = AcquisitionStore(dir())
        assertTrue(store.rename("a", "A grade"))
        assertEquals(sidecar("a", "A grade"), store.load("a")?.sidecar)
    }

    @Test
    fun renameRejectsBlankAndUnknown() {
        put("a", 1_000)
        val store = AcquisitionStore(dir())
        assertFalse(store.rename("a", "  "))
        assertFalse(store.rename("zzz", "x"))
        assertEquals("Race a", store.load("a")?.sidecar?.name)
    }

    @Test
    fun deleteRemovesBothFiles() {
        put("a", 1_000)
        put("b", 2_000)
        val store = AcquisitionStore(dir())
        assertTrue(store.delete("a"))
        assertFalse(File(dir(), "acq_a.mp4").exists())
        assertFalse(File(dir(), "acq_a.json").exists())
        assertEquals(listOf("b"), store.list().map { it.sidecar.id })
        assertFalse(store.delete("a"))
    }

    @Test
    fun saveReplacesTheSidecarAtomicallyAndLeavesNoTempFile() {
        put("a", 1_000)
        val store = AcquisitionStore(dir())
        val edited = sidecar("a").withCrossingAdded(1_000_000, 30)
        store.save(edited)
        assertEquals(edited, store.load("a")?.sidecar)
        assertEquals(listOf("acq_a.json", "acq_a.mp4"), dir().list()!!.sorted())
    }

    @Test
    fun loadOfAnInvalidOrMissingIdIsNull() {
        put("a", 1_000)
        File(dir(), "acq_bad.json").writeText("{")
        File(dir(), "acq_bad.mp4").writeText("v")
        val store = AcquisitionStore(dir())
        assertNull(store.load("bad"))
        assertNull(store.load("missing"))
        assertNull(store.load("../a"))
    }
}
