package net.belllap.bugreport

import java.io.File

/** A file to send. [file] is read at send time, so [size] is always the bytes on disk. */
data class Attachment(val name: String, val kind: AttachmentKind, val contentType: String, val file: File) {
    val size: Long get() = file.length()
}

sealed interface DraftProblem {
    val message: String

    data object EmptyDescription : DraftProblem {
        override val message = "Describe the problem."
    }

    data class DescriptionTooLong(val length: Int) : DraftProblem {
        override val message = "The description has $length characters; the limit is ${Caps.MAX_DESCRIPTION}."
    }

    data class InvalidName(val name: String) : DraftProblem {
        override val message = "\"$name\" is not a valid attachment name."
    }

    data class DuplicateName(val name: String) : DraftProblem {
        override val message = "Two attachments are named \"$name\"."
    }

    data class MissingOrEmpty(val name: String) : DraftProblem {
        override val message = "\"$name\" is missing or empty."
    }

    data class ContentTypeNotAllowed(val name: String, val contentType: String) : DraftProblem {
        override val message = "\"$name\" has the content type $contentType, which is not accepted for its kind."
    }

    data class TooLarge(val name: String, val size: Long, val max: Long) : DraftProblem {
        override val message = "\"$name\" is ${megabytes(size)} MB; the limit is ${megabytes(max)} MB."
    }

    data class TooMany(val kind: AttachmentKind, val max: Int) : DraftProblem {
        override val message = "At most $max ${kind.wire} attachment(s) are allowed."
    }

    data object TooManyAttachments : DraftProblem {
        override val message = "At most ${Caps.MAX_ATTACHMENTS} attachments are allowed."
    }

    data class TotalTooLarge(val size: Long) : DraftProblem {
        override val message = "The attachments total ${megabytes(
            size,
        )} MB; the limit is ${megabytes(Caps.MAX_TOTAL_BYTES)} MB."
    }
}

private const val BYTES_PER_MB = 1024.0 * 1024

private fun megabytes(bytes: Long) = "%.1f".format(bytes / BYTES_PER_MB)

/** What the user filled in. A draft with no problems from [validate] is accepted by the Worker's create call. */
data class ReportDraft(val description: String, val attachments: List<Attachment>) {
    fun validate(): List<DraftProblem> = buildList {
        validateDescription(description)
        val sizes = attachments.associateWith { it.size }
        attachments.forEach { addAll(problemsOf(it, sizes.getValue(it))) }
        attachments.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            add(
                DraftProblem.DuplicateName(it),
            )
        }
        attachments.groupBy { it.kind }.forEach { (kind, list) ->
            val max = Caps.of(kind).maxCount
            if (list.size > max) add(DraftProblem.TooMany(kind, max))
        }
        if (attachments.size > Caps.MAX_ATTACHMENTS) add(DraftProblem.TooManyAttachments)
        val total = sizes.values.sum()
        if (total > Caps.MAX_TOTAL_BYTES) add(DraftProblem.TotalTooLarge(total))
    }

    private fun MutableList<DraftProblem>.validateDescription(text: String) {
        if (text.isBlank()) add(DraftProblem.EmptyDescription)
        if (text.length > Caps.MAX_DESCRIPTION) add(DraftProblem.DescriptionTooLong(text.length))
    }

    private fun problemsOf(a: Attachment, size: Long): List<DraftProblem> = buildList {
        if (!NAME_PATTERN.matches(a.name) || a.name == RESERVED_NAME) add(DraftProblem.InvalidName(a.name))
        if (size <= 0) add(DraftProblem.MissingOrEmpty(a.name))
        val caps = Caps.of(a.kind)
        if (a.contentType !in caps.contentTypes) add(DraftProblem.ContentTypeNotAllowed(a.name, a.contentType))
        if (size > caps.maxBytes) add(DraftProblem.TooLarge(a.name, size, caps.maxBytes))
    }

    companion object {
        val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        const val RESERVED_NAME = "manifest.json"
    }
}
