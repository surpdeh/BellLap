package net.belllap.core.log

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

object AndroidInfo {
    private const val PERCENT = 100
    private const val TENTHS = 10.0

    fun device(appVersion: String) =
        DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, Build.VERSION.RELEASE, appVersion)

    fun battery(context: Context): BatteryInfo? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return null
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return BatteryInfo(
            percent = level * PERCENT / scale,
            charging = charging,
            temperatureC = tenthsC.takeIf { it != Int.MIN_VALUE }?.let { it / TENTHS },
        )
    }

    /** Records exits newer than [sinceMs]; returns the newest exit time seen. API 30+ only. */
    fun recordExitInfo(context: Context, log: EventLog, sinceMs: Long): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return sinceMs
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = am.getHistoricalProcessExitReasons(null, 0, 0)
            .filter { it.timestamp > sinceMs }
        exits.sortedBy { it.timestamp }.forEach {
            val reason = reasonName(it.reason)
            log.log(ExitRecord(reason, it.pid, it.importance, it.status, it.timestamp, it.description))
        }
        return exits.maxOfOrNull { it.timestamp } ?: sinceMs
    }

    fun reasonName(reason: Int): String = EXIT_REASONS[reason] ?: "UNKNOWN($reason)"

    // Values of ApplicationExitInfo.REASON_*, kept as literals so this compiles without API 30 constants.
    private val EXIT_REASONS = mapOf(
        0 to "UNKNOWN", 1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY", 4 to "CRASH",
        5 to "CRASH_NATIVE", 6 to "ANR", 7 to "INITIALIZATION_FAILURE", 8 to "PERMISSION_CHANGE",
        9 to "EXCESSIVE_RESOURCE_USAGE", 10 to "USER_REQUESTED", 11 to "USER_STOPPED",
        12 to "DEPENDENCY_DIED", 13 to "OTHER", 14 to "FREEZER", 15 to "PACKAGE_STATE_CHANGE",
        16 to "PACKAGE_UPDATED",
    )
}
