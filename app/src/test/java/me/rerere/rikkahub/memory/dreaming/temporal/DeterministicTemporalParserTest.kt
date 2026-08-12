package me.rerere.rikkahub.memory.dreaming.temporal

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicTemporalParserTest {
    @Test
    fun `blank is timeless but an invalid IANA zone fails closed`() {
        assertTrue(parse(null, zone = "UTC") is TemporalParseResult.Timeless)
        assertEquals(
            TemporalParseReason.INVALID_TIMEZONE,
            (parse("", zone = "GMT+08:00") as TemporalParseResult.Unknown).reason,
        )
    }

    @Test
    fun `relative expression requires source timestamp and never anchors to frozen now`() {
        val missing = parse("明天", sourceMs = null)
        assertEquals(
            TemporalParseReason.RELATIVE_SOURCE_TIMESTAMP_REQUIRED,
            (missing as TemporalParseResult.Unknown).reason,
        )

        val source = localEpoch("2026-08-12T23:30:00", SHANGHAI)
        val parsed = parse(
            expression = "明天",
            sourceMs = source,
            frozenNowMs = epoch("2030-01-01T00:00:00Z"),
            zone = SHANGHAI,
        ).parsed()
        assertEquals(epoch("2026-08-12T16:00:00Z"), parsed.window.startInclusiveEpochMs)
        assertEquals(epoch("2026-08-13T16:00:00Z"), parsed.window.endExclusiveEpochMs)
        assertEquals(TemporalAnchorKind.SOURCE_TIMESTAMP, parsed.anchorKind)
    }

    @Test
    fun `bounded Chinese tonight morning and next week have exact local windows`() {
        val source = localEpoch("2026-08-12T10:15:00", SHANGHAI)

        assertWindow(
            parse("今晚", source, zone = SHANGHAI),
            "2026-08-12T10:00:00Z",
            "2026-08-12T16:00:00Z",
        )
        assertWindow(
            parse("明早", source, zone = SHANGHAI),
            "2026-08-12T22:00:00Z",
            "2026-08-13T04:00:00Z",
        )
        assertWindow(
            parse("下周", source, zone = SHANGHAI),
            "2026-08-16T16:00:00Z",
            "2026-08-23T16:00:00Z",
        )
        assertWindow(
            parse("下周一", source, zone = SHANGHAI),
            "2026-08-16T16:00:00Z",
            "2026-08-17T16:00:00Z",
        )
    }

    @Test
    fun `month end uses calendar boundaries and leap day remains valid`() {
        val januaryEnd = localEpoch("2026-01-31T23:00:00", SHANGHAI)
        val nextMonth = parse("下个月", januaryEnd, zone = SHANGHAI).parsed().window
        assertEquals(epoch("2026-01-31T16:00:00Z"), nextMonth.startInclusiveEpochMs)
        assertEquals(epoch("2026-02-28T16:00:00Z"), nextMonth.endExclusiveEpochMs)

        val leapEve = localEpoch("2024-02-28T12:00:00", "UTC")
        assertWindow(
            parse("明天", leapEve, zone = "UTC"),
            "2024-02-29T00:00:00Z",
            "2024-03-01T00:00:00Z",
        )
        assertTrue(parse("2024-02-29", zone = "UTC") is TemporalParseResult.Parsed)
        assertEquals(
            TemporalParseReason.INVALID_ISO_DATE,
            (parse("2025-02-29", zone = "UTC") as TemporalParseResult.Unknown).reason,
        )
    }

    @Test
    fun `ISO date and interval are half open and absolute`() {
        assertWindow(
            parse("2026-03-01", zone = "UTC"),
            "2026-03-01T00:00:00Z",
            "2026-03-02T00:00:00Z",
        )
        val interval = parse("2026-03-01/2026-03-03", zone = "UTC").parsed()
        assertEquals(TemporalPrecision.INTERVAL, interval.precision)
        assertEquals(TemporalAnchorKind.ABSOLUTE, interval.anchorKind)
        assertEquals(epoch("2026-03-01T00:00:00Z"), interval.window.startInclusiveEpochMs)
        assertEquals(epoch("2026-03-03T00:00:00Z"), interval.window.endExclusiveEpochMs)
        assertEquals(
            TemporalParseReason.ISO_INTERVAL_NOT_FORWARD,
            (parse("2026-03-03/2026-03-01", zone = "UTC") as
                TemporalParseResult.Unknown).reason,
        )
    }

    @Test
    fun `New York DST days preserve 23 and 25 hour calendar windows`() {
        val spring = parse("2026-03-08", zone = NEW_YORK).parsed().window
        val autumn = parse("2026-11-01", zone = NEW_YORK).parsed().window

        assertEquals(
            Duration.ofHours(23).toMillis(),
            spring.endExclusiveEpochMs - spring.startInclusiveEpochMs,
        )
        assertEquals(
            Duration.ofHours(25).toMillis(),
            autumn.endExclusiveEpochMs - autumn.startInclusiveEpochMs,
        )
    }

    @Test
    fun `DST gap and overlap local date times are rejected instead of guessed`() {
        assertEquals(
            TemporalParseReason.DST_GAP_LOCAL_TIME,
            (parse("2026-03-08T02:30:00", zone = NEW_YORK) as
                TemporalParseResult.Unknown).reason,
        )
        assertEquals(
            TemporalParseReason.DST_OVERLAP_LOCAL_TIME,
            (parse("2026-11-01T01:30:00", zone = NEW_YORK) as
                TemporalParseResult.Unknown).reason,
        )

        val explicitOffset = parse("2026-11-01T01:30:00-04:00", zone = NEW_YORK).parsed()
        assertEquals(epoch("2026-11-01T05:30:00Z"), explicitOffset.window.startInclusiveEpochMs)
    }

    @Test
    fun `parser never scans prose and input bounds fail closed`() {
        assertEquals(
            TemporalParseReason.UNSUPPORTED_EXPRESSION,
            (parse("我们下周去上海", sourceMs = epoch("2026-08-12T00:00:00Z")) as
                TemporalParseResult.Unknown).reason,
        )
        assertEquals(
            TemporalParseReason.EXPRESSION_TOO_LONG,
            (parse("下".repeat(MAX_TEMPORAL_EXPRESSION_LENGTH + 1)) as
                TemporalParseResult.Unknown).reason,
        )
        assertEquals(
            TemporalParseReason.EXPRESSION_CONTAINS_CONTROL_CHARACTER,
            (parse("明天\u0000") as TemporalParseResult.Unknown).reason,
        )
    }

    @Test
    fun `same frozen inputs produce byte-for-byte equal value results after restart`() {
        val request = TemporalParseRequest(
            expression = "下周五",
            frozenNowEpochMs = epoch("2026-08-12T02:00:00Z"),
            sourceTimestampEpochMs = epoch("2026-08-12T01:00:00Z"),
            timezoneId = SHANGHAI,
        )
        assertEquals(
            DeterministicTemporalParser.parse(request),
            DeterministicTemporalParser.parse(request.copy()),
        )
    }

    private fun parse(
        expression: String?,
        sourceMs: Long? = epoch("2026-08-12T00:00:00Z"),
        frozenNowMs: Long = epoch("2026-08-12T00:00:00Z"),
        zone: String = "UTC",
    ): TemporalParseResult = DeterministicTemporalParser.parse(
        TemporalParseRequest(
            expression = expression,
            frozenNowEpochMs = frozenNowMs,
            sourceTimestampEpochMs = sourceMs,
            timezoneId = zone,
        ),
    )

    private fun assertWindow(result: TemporalParseResult, start: String, end: String) {
        val window = result.parsed().window
        assertEquals(epoch(start), window.startInclusiveEpochMs)
        assertEquals(epoch(end), window.endExclusiveEpochMs)
    }

    private fun TemporalParseResult.parsed() = this as TemporalParseResult.Parsed

    private fun epoch(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun localEpoch(iso: String, zone: String): Long =
        LocalDateTime.parse(iso).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private companion object {
        const val SHANGHAI = "Asia/Shanghai"
        const val NEW_YORK = "America/New_York"
    }
}
