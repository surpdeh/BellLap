package net.belllap.bugreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportDraftTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var counter = 0

    private fun att(name: String, kind: AttachmentKind, type: String, bytes: Int = 10) = Attachment(
        name,
        kind,
        type,
        tmp.newFile("f${counter++}").apply { writeBytes(ByteArray(bytes) { 1 }) },
    )

    @Test
    fun validDraftHasNoProblems() {
        val draft =
            ReportDraft("late bell", listOf(att("capabilities.json", AttachmentKind.CAPABILITIES, "application/json")))
        assertTrue(draft.validate().isEmpty())
    }

    @Test
    fun descriptionIsRequiredAndBounded() {
        assertEquals(listOf(DraftProblem.EmptyDescription), ReportDraft("  ", emptyList()).validate())
        val long = "x".repeat(Caps.MAX_DESCRIPTION + 1)
        assertEquals(listOf(DraftProblem.DescriptionTooLong(long.length)), ReportDraft(long, emptyList()).validate())
    }

    @Test
    fun namesContentTypesAndCountsAreChecked() {
        val draft = ReportDraft(
            "d",
            listOf(
                att("manifest.json", AttachmentKind.TRACE, "application/zip"),
                att("bad name.zip", AttachmentKind.TRACE, "application/zip"),
                att("a.bin", AttachmentKind.BELL, "video/mp4"),
                att("a.bin", AttachmentKind.BELL, "audio/wav"),
            ),
        )
        val problems = draft.validate()
        assertTrue(DraftProblem.InvalidName("manifest.json") in problems)
        assertTrue(DraftProblem.InvalidName("bad name.zip") in problems)
        assertTrue(DraftProblem.ContentTypeNotAllowed("a.bin", "video/mp4") in problems)
        assertTrue(DraftProblem.DuplicateName("a.bin") in problems)
        assertTrue(DraftProblem.TooMany(AttachmentKind.BELL, 1) in problems)
    }

    @Test
    fun emptyOrMissingFileIsRejected() {
        val missing = Attachment("gone.zip", AttachmentKind.TRACE, "application/zip", tmp.root.resolve("nope"))
        assertEquals(listOf(DraftProblem.MissingOrEmpty("gone.zip")), ReportDraft("d", listOf(missing)).validate())
    }

    @Test
    fun oversizeAttachmentIsRejected() {
        val big = att("cap.json", AttachmentKind.CAPABILITIES, "application/json", bytes = 1024 * 1024 + 1)
        val problems = ReportDraft("d", listOf(big)).validate()
        assertEquals(listOf(DraftProblem.TooLarge("cap.json", big.size, 1024L * 1024)), problems)
    }
}
