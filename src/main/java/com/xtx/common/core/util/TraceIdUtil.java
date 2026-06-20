package com.xtx.common.core.util;

import com.xtx.common.core.constant.CommonConstant;
import org.slf4j.MDC;

import java.util.UUID;

public final class TraceIdUtil {

    private TraceIdUtil() {
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void put(String traceId) {
        MDC.put(CommonConstant.TRACE_ID, traceId);
    }

    public static String get() {
        String traceId = MDC.get(CommonConstant.TRACE_ID);
        return traceId == null || traceId.isBlank() ? generateTraceId() : traceId;
    }

    public static void clear() {
        MDC.remove(CommonConstant.TRACE_ID);
    }
}
