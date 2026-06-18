package com.xtx.mock.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MockTime — 统一时间格式化工具。
 *
 * 职责：
 * 1. 所有 Mock 服务响应中的时间字段统一使用 YYYY-MM-DD HH:mm:ss 格式；
 * 2. 避免各处分散 new Date() / SimpleDateFormat；
 * 3. 方便后续统一时区调整。
 *
 * 使用方式：
 * <pre>
 *   String now = MockTime.now();              // "2026-06-16 14:30:00"
 *   String ts  = MockTime.format(someDate);   // 格式化任意 LocalDateTime
 * </pre>
 */
public class MockTime {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MockTime() {
        // utility class
    }

    /**
     * Returns the current timestamp as a formatted string.
     */
    public static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }

    /**
     * Formats a given LocalDateTime into the standard Mock timestamp format.
     */
    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? now() : dateTime.format(FORMATTER);
    }

    /**
     * Returns the current timestamp as a formatted string (alias for consistency).
     */
    public static String currentTime() {
        return now();
    }
}
