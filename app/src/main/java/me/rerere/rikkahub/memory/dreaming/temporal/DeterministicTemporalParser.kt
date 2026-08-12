package me.rerere.rikkahub.memory.dreaming.temporal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

/** Strict, bounded parser. It never scans prose and never falls back to a system clock/zone. */
object DeterministicTemporalParser {
    private val isoDatePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val isoDateTimePrefix = Regex("^\\d{4}-\\d{2}-\\d{2}T.+$")
    private val isoLookingPrefix = Regex("^\\d{4}-.*$")
    private val relativeExpressions = setOf(
        "今天",
        "今早",
        "今晚",
        "明天",
        "明早",
        "明晚",
        "后天",
        "本周",
        "下周",
        "本月",
        "下月",
        "下个月",
        "本周一",
        "本周二",
        "本周三",
        "本周四",
        "本周五",
        "本周六",
        "本周日",
        "下周一",
        "下周二",
        "下周三",
        "下周四",
        "下周五",
        "下周六",
        "下周日",
    )

    fun parse(request: TemporalParseRequest): TemporalParseResult {
        val zone = strictZoneOrNull(request.timezoneId)
            ?: return request.unknown(TemporalParseReason.INVALID_TIMEZONE)
        val rawExpression = request.expression.orEmpty()
        if (rawExpression.length > MAX_TEMPORAL_EXPRESSION_LENGTH) {
            return request.unknown(TemporalParseReason.EXPRESSION_TOO_LONG)
        }
        if (rawExpression.any(Char::isISOControl)) {
            return request.unknown(TemporalParseReason.EXPRESSION_CONTAINS_CONTROL_CHARACTER)
        }
        val expression = rawExpression.trim()
        if (expression.isEmpty()) return request.timeless()
        if (expression in relativeExpressions) {
            val sourceTimestamp = request.sourceTimestampEpochMs
                ?: return request.unknown(
                    TemporalParseReason.RELATIVE_SOURCE_TIMESTAMP_REQUIRED,
                )
            return parseRelative(request, expression, sourceTimestamp, zone)
        }
        if ('/' in expression) return parseIsoInterval(request, expression, zone)
        if (isoDatePattern.matches(expression)) return parseIsoDate(request, expression, zone)
        if (isoDateTimePrefix.matches(expression)) {
            return parseIsoDateTime(request, expression, zone)
        }
        return request.unknown(
            if (isoLookingPrefix.matches(expression)) {
                TemporalParseReason.INVALID_ISO_DATE_TIME
            } else {
                TemporalParseReason.UNSUPPORTED_EXPRESSION
            },
        )
    }

    private fun parseRelative(
        request: TemporalParseRequest,
        expression: String,
        sourceTimestampEpochMs: Long,
        zone: ZoneId,
    ): TemporalParseResult {
        val sourceDate = Instant.ofEpochMilli(sourceTimestampEpochMs).atZone(zone).toLocalDate()
        val resolved = when (expression) {
            "今天" -> dateWindow(sourceDate, zone) to TemporalPrecision.DATE
            "明天" -> dateWindow(sourceDate.plusDays(1), zone) to TemporalPrecision.DATE
            "后天" -> dateWindow(sourceDate.plusDays(2), zone) to TemporalPrecision.DATE
            "今早" -> partOfDayWindow(sourceDate, LocalTime.of(6, 0), LocalTime.NOON, zone) to
                TemporalPrecision.PART_OF_DAY
            "今晚" -> partOfDayWindow(
                sourceDate,
                LocalTime.of(18, 0),
                LocalTime.MIDNIGHT,
                zone,
                endOnNextDay = true,
            ) to TemporalPrecision.PART_OF_DAY
            "明早" -> partOfDayWindow(
                sourceDate.plusDays(1),
                LocalTime.of(6, 0),
                LocalTime.NOON,
                zone,
            ) to TemporalPrecision.PART_OF_DAY
            "明晚" -> partOfDayWindow(
                sourceDate.plusDays(1),
                LocalTime.of(18, 0),
                LocalTime.MIDNIGHT,
                zone,
                endOnNextDay = true,
            ) to TemporalPrecision.PART_OF_DAY
            "本周" -> weekWindow(sourceDate, plusWeeks = 0, zone) to TemporalPrecision.WEEK
            "下周" -> weekWindow(sourceDate, plusWeeks = 1, zone) to TemporalPrecision.WEEK
            "本月" -> monthWindow(sourceDate, plusMonths = 0, zone) to TemporalPrecision.MONTH
            "下月", "下个月" ->
                monthWindow(sourceDate, plusMonths = 1, zone) to TemporalPrecision.MONTH
            else -> {
                val nextWeek = expression.startsWith("下周")
                val dayText = expression.removePrefix(if (nextWeek) "下周" else "本周")
                val dayOffset = chineseWeekdayOffset(dayText)
                    ?: return request.unknown(TemporalParseReason.UNSUPPORTED_EXPRESSION)
                val monday = sourceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusWeeks(if (nextWeek) 1L else 0L)
                dateWindow(monday.plusDays(dayOffset.toLong()), zone) to TemporalPrecision.DATE
            }
        }
        return when (val window = resolved.first) {
            is Resolution.Value -> request.parsed(
                reason = TemporalParseReason.CHINESE_RELATIVE_PARSED,
                window = window.value,
                precision = resolved.second,
                anchorKind = TemporalAnchorKind.SOURCE_TIMESTAMP,
            )
            is Resolution.Error -> request.unknown(window.reason)
        }
    }

    private fun parseIsoDate(
        request: TemporalParseRequest,
        expression: String,
        zone: ZoneId,
    ): TemporalParseResult {
        val date = try {
            LocalDate.parse(expression, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            return request.unknown(TemporalParseReason.INVALID_ISO_DATE)
        }
        return when (val window = dateWindow(date, zone)) {
            is Resolution.Value -> request.parsed(
                reason = TemporalParseReason.ISO_DATE_PARSED,
                window = window.value,
                precision = TemporalPrecision.DATE,
                anchorKind = TemporalAnchorKind.ABSOLUTE,
            )
            is Resolution.Error -> request.unknown(window.reason)
        }
    }

    private fun parseIsoDateTime(
        request: TemporalParseRequest,
        expression: String,
        zone: ZoneId,
    ): TemporalParseResult = when (val instant = isoEndpoint(expression, zone)) {
        // Epoch millis are the storage precision, so an ISO date-time is a one-millisecond event.
        is Resolution.Value -> request.parsed(
            reason = TemporalParseReason.ISO_DATE_TIME_PARSED,
            window = TemporalWindow(instant.value, instant.value + 1L),
            precision = TemporalPrecision.DATE_TIME,
            anchorKind = TemporalAnchorKind.ABSOLUTE,
        )
        is Resolution.Error -> request.unknown(instant.reason)
    }

    private fun parseIsoInterval(
        request: TemporalParseRequest,
        expression: String,
        zone: ZoneId,
    ): TemporalParseResult {
        val endpoints = expression.split('/', limit = 3)
        if (endpoints.size != 2 || endpoints.any(String::isEmpty)) {
            return request.unknown(TemporalParseReason.INVALID_ISO_INTERVAL)
        }
        val start = when (val result = isoEndpoint(endpoints[0], zone)) {
            is Resolution.Value -> result.value
            is Resolution.Error -> return request.unknown(result.reason)
        }
        val end = when (val result = isoEndpoint(endpoints[1], zone)) {
            is Resolution.Value -> result.value
            is Resolution.Error -> return request.unknown(result.reason)
        }
        if (end <= start) {
            return request.unknown(TemporalParseReason.ISO_INTERVAL_NOT_FORWARD)
        }
        return request.parsed(
            reason = TemporalParseReason.ISO_INTERVAL_PARSED,
            window = TemporalWindow(start, end),
            precision = TemporalPrecision.INTERVAL,
            anchorKind = TemporalAnchorKind.ABSOLUTE,
        )
    }

    /** A date interval endpoint means local start-of-day; an interval is always half-open. */
    private fun isoEndpoint(text: String, zone: ZoneId): Resolution<Long> {
        if (isoDatePattern.matches(text)) {
            val date = try {
                LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: DateTimeParseException) {
                return Resolution.Error(TemporalParseReason.INVALID_ISO_DATE)
            }
            return localInstant(date.atStartOfDay(), zone)
        }
        if (!isoDateTimePrefix.matches(text)) {
            return Resolution.Error(TemporalParseReason.INVALID_ISO_INTERVAL)
        }
        try {
            return Resolution.Value(
                OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant()
                    .toEpochMilli(),
            )
        } catch (_: DateTimeParseException) {
            // A zone-less local ISO date-time is resolved below against the explicit IANA zone.
        } catch (_: ArithmeticException) {
            return Resolution.Error(TemporalParseReason.EPOCH_MILLIS_OUT_OF_RANGE)
        }
        val local = try {
            LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (_: DateTimeParseException) {
            return Resolution.Error(TemporalParseReason.INVALID_ISO_DATE_TIME)
        }
        return localInstant(local, zone)
    }

    private fun dateWindow(date: LocalDate, zone: ZoneId): Resolution<TemporalWindow> =
        localWindow(date.atStartOfDay(), date.plusDays(1).atStartOfDay(), zone)

    private fun partOfDayWindow(
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        zone: ZoneId,
        endOnNextDay: Boolean = false,
    ): Resolution<TemporalWindow> = localWindow(
        date.atTime(start),
        (if (endOnNextDay) date.plusDays(1) else date).atTime(end),
        zone,
    )

    private fun weekWindow(
        date: LocalDate,
        plusWeeks: Long,
        zone: ZoneId,
    ): Resolution<TemporalWindow> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(plusWeeks)
        return localWindow(start.atStartOfDay(), start.plusWeeks(1).atStartOfDay(), zone)
    }

    private fun monthWindow(
        date: LocalDate,
        plusMonths: Long,
        zone: ZoneId,
    ): Resolution<TemporalWindow> {
        val start = date.withDayOfMonth(1).plusMonths(plusMonths)
        return localWindow(start.atStartOfDay(), start.plusMonths(1).atStartOfDay(), zone)
    }

    private fun localWindow(
        start: LocalDateTime,
        end: LocalDateTime,
        zone: ZoneId,
    ): Resolution<TemporalWindow> {
        val resolvedStart = when (val result = localInstant(start, zone)) {
            is Resolution.Value -> result.value
            is Resolution.Error -> return result
        }
        val resolvedEnd = when (val result = localInstant(end, zone)) {
            is Resolution.Value -> result.value
            is Resolution.Error -> return result
        }
        if (resolvedEnd <= resolvedStart) {
            return Resolution.Error(TemporalParseReason.INVALID_ISO_INTERVAL)
        }
        return Resolution.Value(TemporalWindow(resolvedStart, resolvedEnd))
    }

    private fun localInstant(local: LocalDateTime, zone: ZoneId): Resolution<Long> {
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return Resolution.Error(TemporalParseReason.DST_GAP_LOCAL_TIME)
        if (offsets.size != 1) {
            return Resolution.Error(TemporalParseReason.DST_OVERLAP_LOCAL_TIME)
        }
        return try {
            Resolution.Value(local.toInstant(offsets.single()).toEpochMilli())
        } catch (_: ArithmeticException) {
            Resolution.Error(TemporalParseReason.EPOCH_MILLIS_OUT_OF_RANGE)
        }
    }

    private fun chineseWeekdayOffset(text: String): Int? = when (text) {
        "一" -> 0
        "二" -> 1
        "三" -> 2
        "四" -> 3
        "五" -> 4
        "六" -> 5
        "日" -> 6
        else -> null
    }
}

internal fun strictZoneOrNull(timezoneId: String): ZoneId? {
    if (timezoneId != "UTC" && (!timezoneId.contains('/') || timezoneId !in IANA_ZONE_IDS)) {
        return null
    }
    return runCatching { ZoneId.of(timezoneId) }.getOrNull()
}

private val IANA_ZONE_IDS: Set<String> = ZoneId.getAvailableZoneIds()
    .filter { it.contains('/') }
    .toSet()

private sealed interface Resolution<out T> {
    data class Value<T>(val value: T) : Resolution<T>
    data class Error(val reason: TemporalParseReason) : Resolution<Nothing>
}

private fun TemporalParseRequest.parsed(
    reason: TemporalParseReason,
    window: TemporalWindow,
    precision: TemporalPrecision,
    anchorKind: TemporalAnchorKind,
) = TemporalParseResult.Parsed(
    frozenNowEpochMs = frozenNowEpochMs,
    sourceTimestampEpochMs = sourceTimestampEpochMs,
    timezoneId = timezoneId,
    reason = reason,
    window = window,
    precision = precision,
    anchorKind = anchorKind,
)

private fun TemporalParseRequest.timeless() = TemporalParseResult.Timeless(
    frozenNowEpochMs = frozenNowEpochMs,
    sourceTimestampEpochMs = sourceTimestampEpochMs,
    timezoneId = timezoneId,
)

private fun TemporalParseRequest.unknown(reason: TemporalParseReason) =
    TemporalParseResult.Unknown(
        frozenNowEpochMs = frozenNowEpochMs,
        sourceTimestampEpochMs = sourceTimestampEpochMs,
        timezoneId = timezoneId,
        reason = reason,
    )
