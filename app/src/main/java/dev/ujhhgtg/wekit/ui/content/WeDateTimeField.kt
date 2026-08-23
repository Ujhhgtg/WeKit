package dev.ujhhgtg.wekit.ui.content

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Schedule
import dev.ujhhgtg.wekit.R
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val MINUTES_PER_DAY: Int = 24 * 60

/** Text pattern a [WeDateTimeField] accepts, and which native picker chain its icon opens. */
enum class WeDateTimeMode(val pattern: String) {
    /** A time of day, `HH:mm`. Opens a [TimePickerDialog]. */
    TIME_ONLY("HH:mm"),

    /** A full timestamp, `yyyy-MM-dd HH:mm:ss`. Opens a [DatePickerDialog] then a [TimePickerDialog]. */
    DATE_TIME("yyyy-MM-dd HH:mm:ss"),
}

/**
 * The module's single date/time input: a freely editable text field that flags unparseable input,
 * plus a trailing clock button opening the native picker(s) for [mode].
 *
 * The text is the source of truth, so a half-typed value is preserved instead of being snapped back.
 * Callers holding a parsed value should use [WeTimeOfDayField] rather than driving this directly.
 */
@Composable
fun WeDateTimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    mode: WeDateTimeMode,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val isError = value.isNotBlank() && when (mode) {
        WeDateTimeMode.TIME_ONLY -> parseMinuteOfDay(value) == null
        WeDateTimeMode.DATE_TIME -> parseDateTime(value) == null
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError) {
            { Text(stringResource(R.string.date_time_invalid_format, mode.pattern)) }
        } else null,
        trailingIcon = {
            IconButton(
                enabled = enabled,
                onClick = {
                    when (mode) {
                        WeDateTimeMode.TIME_ONLY -> showNativeTimePicker(
                            context = context,
                            initialMinute = parseMinuteOfDay(value) ?: currentMinuteOfDay(),
                            onSelected = { onValueChange(formatMinuteOfDay(it)) },
                        )

                        WeDateTimeMode.DATE_TIME -> showNativeDateTimePicker(
                            context = context,
                            initialMillis = parseDateTime(value) ?: System.currentTimeMillis(),
                            onSelected = { onValueChange(formatDateTime(it)) },
                        )
                    }
                },
            ) {
                Icon(
                    MaterialSymbols.Outlined.Schedule,
                    contentDescription = stringResource(R.string.date_time_choose_time),
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
    )
}

/**
 * [WeDateTimeField] in [WeDateTimeMode.TIME_ONLY] for callers that store a minute-of-day [Int].
 *
 * Keeps the in-progress text locally and reports back only once it parses, so typing never pushes a
 * garbage value into the caller's state. An external change to [minuteOfDay] (a rule reset, say)
 * resyncs the text; typing does not, since the effect is keyed on the incoming value alone.
 */
@Composable
fun WeTimeOfDayField(
    minuteOfDay: Int,
    onMinuteChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by remember { mutableStateOf(formatMinuteOfDay(minuteOfDay)) }

    LaunchedEffect(minuteOfDay) {
        if (parseMinuteOfDay(text) != minuteOfDay) text = formatMinuteOfDay(minuteOfDay)
    }

    WeDateTimeField(
        value = text,
        onValueChange = { next ->
            text = next
            parseMinuteOfDay(next)?.let(onMinuteChange)
        },
        label = label,
        mode = WeDateTimeMode.TIME_ONLY,
        modifier = modifier,
        enabled = enabled,
    )
}

/** Formats a minute-of-day as `HH:mm`, coercing out-of-range input into the day. */
fun formatMinuteOfDay(value: Int): String {
    val minute = value.coerceIn(0, MINUTES_PER_DAY - 1)
    return "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
}

/** Parses `H:mm` / `HH:mm` into a minute-of-day, or null when the text isn't a valid time. */
fun parseMinuteOfDay(text: String): Int? {
    val match = TIME_OF_DAY_REGEX.matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour > 23 || minute > 59) return null
    return hour * 60 + minute
}

fun formatDateTime(millis: Long): String = dateTimeFormat().format(Date(millis))

/**
 * Parses `yyyy-MM-dd HH:mm:ss` into epoch millis, or null when the text isn't a valid timestamp.
 * Strict: rolled-over fields (month 13) and trailing garbage are both rejected.
 */
fun parseDateTime(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val position = ParsePosition(0)
    val parsed = dateTimeFormat().parse(trimmed, position) ?: return null
    if (position.index != trimmed.length) return null
    return parsed.time
}

private fun dateTimeFormat() =
    SimpleDateFormat(WeDateTimeMode.DATE_TIME.pattern, Locale.getDefault()).apply { isLenient = false }

/** The current wall-clock time as a minute-of-day, the shape [WeTimeOfDayField] works in. */
internal fun currentMinuteOfDay(): Int = Calendar.getInstance().let {
    it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
}

private fun showNativeTimePicker(context: Context, initialMinute: Int, onSelected: (Int) -> Unit) {
    val minute = initialMinute.coerceIn(0, MINUTES_PER_DAY - 1)
    TimePickerDialog(
        context,
        { _, hour, selectedMinute -> onSelected(hour * 60 + selectedMinute) },
        minute / 60,
        minute % 60,
        true
    ).show()
}

private fun showNativeDateTimePicker(context: Context, initialMillis: Long, onSelected: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initialMillis }

    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)

            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    onSelected(calendar.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private val TIME_OF_DAY_REGEX = Regex("""(\d{1,2}):(\d{2})""")
