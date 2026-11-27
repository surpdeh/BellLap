package net.belllap.ui

import android.Manifest

/** What the setup screen shows for the camera and microphone permissions. */
enum class PermissionUi {
    GRANTED,

    /** Not granted yet, or denied once: explain why, then ask. */
    RATIONALE,

    /** Denied with "don't ask again": only system settings can grant it. */
    BLOCKED,
}

val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/**
 * [alreadyAsked] is true once the system dialog has been shown this install. A denied permission
 * with no rationale to show after an ask means "don't ask again".
 */
fun permissionUi(allGranted: Boolean, alreadyAsked: Boolean, shouldShowRationale: Boolean): PermissionUi = when {
    allGranted -> PermissionUi.GRANTED
    alreadyAsked && !shouldShowRationale -> PermissionUi.BLOCKED
    else -> PermissionUi.RATIONALE
}
