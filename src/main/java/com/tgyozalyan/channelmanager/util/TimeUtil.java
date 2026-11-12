package com.tgyozalyan.channelmanager.util;

import org.apache.commons.lang3.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static java.time.ZoneOffset.UTC;

public class TimeUtil {
    private static final long WINDOWS_FILE_TIME_TICKS_PER_MILLISECOND = 10_000;
    private static final long UNIX_TIME_OFFSET_MILLIS = TimeUnit.SECONDS.toMillis(11644473600L);
    private static final GregorianCalendar GREGORIAN_CALENDAR = new GregorianCalendar(TimeZone.getTimeZone("UTC"));

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC)
                // Some JVM implementations on some OSes support time up to nanoseconds, which makes them
                // inconsistent between each other. And also Postgres stores data up to microseconds, so while
                // we can generate time with nano resolution, when it gets back from DB it won't have nanos.
                // This causes issues with tests which check whether the date is stored correctly - as it doesn't
                // match what we have in tests.
                //
                // So let's truncate to micros to be consistent across all OSes and DBs.
                .truncatedTo(ChronoUnit.MICROS);
    }
    public static Instant utcNow() {
        // Some JVM implementations on some OSes support time up to nanoseconds, which makes them
        // inconsistent between each other. And also Postgres stores data up to microseconds, so while
        // we can generate time with nano resolution, when it gets back from DB it won't have nanos.
        // This causes issues with tests which check whether the date is stored correctly - as it doesn't
        // match what we have in tests.
        //
        // So let's truncate to micros to be consistent across all OSes and DBs.
        return Clock.systemUTC().instant().truncatedTo(ChronoUnit.MICROS);
    }
    public static Instant utcDateNow() {
        return utcNow().truncatedTo(ChronoUnit.DAYS);
    }
    /**
     * Returns the current date in UTC timezone.
     */
    public static LocalDate dateNow() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    public static Instant fromIsoInstant(String isoInstantString) {
        if (StringUtils.isBlank(isoInstantString))
            return null;
        return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(isoInstantString));
    }
    public static long toMicros(LocalDateTime dateTime) {
        Instant instant = dateTime.toInstant(UTC);
        return TimeUnit.SECONDS.toMicros(instant.getEpochSecond()) + TimeUnit.NANOSECONDS.toMicros(instant.getNano());
    }

    public static LocalDateTime fromMicros(long micros) {
        Duration duration = Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(micros));
        return Instant.EPOCH.plus(duration).atZone(UTC).toLocalDateTime();
    }
    public static LocalDateTime fromSeconds(long seconds) {
        return Instant.EPOCH.plusSeconds(seconds).atZone(UTC).toLocalDateTime();
    }
    /**
     * Turns date into a standard format to be used for any external communication.
     */
    public static String formatApiDate(OffsetDateTime dateTime) {
        if (dateTime == null)
            return null;
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
    }

    public static String formatApiDate(Instant instant) {
        if (instant == null)
            return null;
        return formatApiDate(OffsetDateTime.ofInstant(instant, UTC));
    }

    /**
     * <a href="https://learn.microsoft.com/en-us/windows/win32/api/minwinbase/ns-minwinbase-filetime"
     * >Windows File Time</a>
     */
    public static long windowsFileTimeToUnixEpochSeconds(long dwHighDateTime, long dwLowDateTime) {
        long ticks = (dwHighDateTime << 32) + (dwLowDateTime & 0xFFFFFFFFL);
        return windowsFileTimeToUnixEpochSeconds(ticks);
    }
    public static long windowsFileTimeToUnixEpochSeconds(long windowsFileTime) {
        long epochMillis = windowsFileTime / WINDOWS_FILE_TIME_TICKS_PER_MILLISECOND - UNIX_TIME_OFFSET_MILLIS;
        return Instant.ofEpochMilli(epochMillis).getEpochSecond();
    }

    public static long fromResultSet(ResultSet rs, String columnName) throws SQLException {
        Timestamp ts = getTimestampFromResultSet(rs, columnName);
        return ts != null ? ts.getTime() : 0;
    }
    public static String fromResultSetFormatted(ResultSet rs, String columnName) throws SQLException {
        Timestamp ts = getTimestampFromResultSet(rs, columnName);
        if (ts == null)
            return null;
        OffsetDateTime dateTime = OffsetDateTime.ofInstant(ts.toInstant(), UTC);
        return formatApiDate(dateTime);
    }

    private static Timestamp getTimestampFromResultSet(ResultSet rs, String columnName) throws SQLException {
        // It's important that we pass Calender with UTC0 here. Otherwise a system default is going to be taken,
        // and the milliseconds in DB will be treated as non-UTC0. Which isn't correct.
        return rs.getTimestamp(columnName, GREGORIAN_CALENDAR);
    }
}
