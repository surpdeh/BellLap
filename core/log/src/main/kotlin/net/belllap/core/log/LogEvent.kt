package net.belllap.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface LogEvent

@Serializable
@SerialName("transition")
data class StateTransition(
    val from: String,
    val to: String,
    val trigger: String,
    val ptsNs: Long? = null,
) : LogEvent

@Serializable
@SerialName("score")
data class DetectorScore(
    val detector: String,
    val score: Double,
    val ptsNs: Long,
) : LogEvent

@Serializable
@SerialName("device")
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val appVersion: String,
) : LogEvent

@Serializable
@SerialName("battery")
data class BatteryInfo(
    val percent: Int,
    val charging: Boolean,
    val temperatureC: Double? = null,
) : LogEvent

@Serializable
@SerialName("exit")
data class ExitRecord(
    val reason: String,
    val pid: Int,
    val importance: Int,
    val status: Int,
    val exitedAtMs: Long,
    val description: String? = null,
) : LogEvent

@Serializable
data class LogEntry(
    val tMs: Long,
    val event: LogEvent,
)
