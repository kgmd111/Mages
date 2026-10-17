package org.mlm.mages.ui.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import mages.shared.generated.resources.*
import okio.Path.Companion.toPath
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

/**
 * Provided from settings so the clock preference reaches every timestamp
 * without threading a flag through each call site.
 */
val LocalUse24HourTime = compositionLocalOf { true }

@Composable
fun formatClockTime(hour: Int, minute: Int): String {
    if (LocalUse24HourTime.current) return "${pad2(hour)}:${pad2(minute)}"
    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val marker = stringResource(if (hour < 12) Res.string.time_am else Res.string.time_pm)
    return stringResource(Res.string.time_12h_format, hour12, pad2(minute), marker)
}

@OptIn(ExperimentalTime::class)
@Composable
fun formatTime(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return formatClockTime(local.hour, local.minute)
}

@OptIn(ExperimentalTime::class)
@Composable
fun formatDate(timestampMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestampMs)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val yesterday = Clock.System.now().minus(1.days).toLocalDateTime(TimeZone.currentSystemDefault()).date

    return when (localDateTime.date) {
        today -> stringResource(Res.string.today)
        yesterday -> stringResource(Res.string.yesterday)
        else -> {
            val month = localDateTime.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
            "${localDateTime.day} $month ${localDateTime.year}"
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun formatPinnedTimestamp(timestampMs: Long): String {
    val local = Instant.fromEpochMilliseconds(timestampMs)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val yesterday = Clock.System.now().minus(1.days)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    val time = formatClockTime(local.hour, local.minute)
    val day = when (local.date) {
        today -> return time
        yesterday -> stringResource(Res.string.yesterday)
        else -> {
            val month = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
            "${local.day} $month ${local.year}"
        }
    }
    return "$time ($day)"
}

fun formatDuration(ms: Long): String {
    val secs = (ms / 1000).toInt()
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "$h:${pad2(m)}:${pad2(s)}" else "$m:${pad2(s)}"
}

fun fileName(path: String): String = path.toPath().name

@OptIn(ExperimentalTime::class)
fun monthYearLabel(timestampMs: Long): String {
    val local = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$month ${local.year}"
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
}

@Composable
fun formatTypingText(users: List<String>): String = when (users.size) {
    0 -> ""
    1 -> stringResource(Res.string.typing_one, users[0])
    2 -> stringResource(Res.string.typing_two, users[0], users[1])
    else -> stringResource(Res.string.typing_many, users[0], users[1], users.size - 2)
}

@Composable
fun formatSeenBy(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> stringResource(Res.string.seen_by_one, names[0])
    2 -> stringResource(Res.string.seen_by_two, names[0], names[1])
    else -> stringResource(Res.string.seen_by_many, names[0], names[1], names.size - 2)
}

@OptIn(ExperimentalTime::class)
fun formatTimelineDate(
    timestampMs: Long,
    todayLabel: String,
    yesterdayLabel: String,
): String {
    val instant = Instant.fromEpochMilliseconds(timestampMs)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val yesterday = Clock.System.now().minus(1.days).toLocalDateTime(TimeZone.currentSystemDefault()).date

    return when (localDateTime.date) {
        today -> todayLabel
        yesterday -> yesterdayLabel
        else -> {
            val month = localDateTime.month.name.lowercase()
                .replaceFirstChar { it.uppercase() }
                .take(3)
            "${localDateTime.day} $month ${localDateTime.year}"
        }
    }
}


fun formatAbsoluteDateTime(timestampMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(timestampMs)
        .toLocalDateTime(TimeZone.currentSystemDefault())

    fun Int.pad2(): String = toString().padStart(2, '0')

    return buildString {
        append(dt.year)
        append("-")
        append(dt.month.number.pad2())
        append("-")
        append(dt.day.pad2())
        append(" ")
        append(dt.hour.pad2())
        append(":")
        append(dt.minute.pad2())
    }
}

fun formatBytes(sizeBytes: Long?): String? {
    if (sizeBytes == null) return null
    if (sizeBytes < 1024) return "$sizeBytes B"

    val units = listOf("KB", "MB", "GB", "TB")
    var value = sizeBytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    val rounded = if (value >= 10) {
        value.roundToInt().toString()
    } else {
        ((value * 10).roundToInt() / 10.0).toString()
    }

    return "$rounded ${units[unitIndex]}"
}

fun formatDimensions(width: Int?, height: Int?): String? {
    if (width == null || height == null) return null
    return "${width}×${height}"
}

fun formatDurationMs(durationMs: Long?): String? {
    if (durationMs == null) return null

    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    fun Long.pad2(): String = toString().padStart(2, '0')

    return if (hours > 0) {
        "${hours.pad2()}:${minutes.pad2()}:${seconds.pad2()}"
    } else {
        "${minutes.pad2()}:${seconds.pad2()}"
    }
}

fun readableEnumName(raw: String): String {
    if (raw.isBlank()) return raw

    return raw
        .replace("_", " ")
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase()
        .replaceFirstChar { it.uppercase() }
}
