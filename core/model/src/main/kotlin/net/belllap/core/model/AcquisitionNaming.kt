package net.belllap.core.model

/**
 * Auto-names acquisitions "<label> Finish N", where [label] is the event name or the date and N counts the finishes
 * already saved under that label (F17). Pure, so the numbering is unit tested without a device.
 */
object AcquisitionNaming {
    /** The next free name for [label], given the [existing] acquisition names (any label). */
    fun nextName(label: String, existing: Collection<String>): String {
        val prefix = "$label Finish "
        val highest = existing.mapNotNull { name ->
            name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return "$prefix${highest + 1}"
    }
}
