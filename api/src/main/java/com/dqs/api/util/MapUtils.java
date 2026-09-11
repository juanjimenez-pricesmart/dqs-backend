package com.dqs.api.util;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static helpers for safely coercing untyped {@code Map<String, Object>} values
 * (JDBC rows, JSON payloads, request bodies) to Java primitives.
 *
 * All methods return a safe default instead of throwing when the value is null
 * or unparseable.
 */
public final class MapUtils {

    private MapUtils() {}

    /** Coerce to int; returns 0 for null or unparseable input. */
    public static int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Coerce to long; returns 0 for null or unparseable input. */
    public static long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }

    /** Coerce to double; returns 0.0 for null or unparseable input. */
    public static double toDouble(Object val) {
        if (val == null) return 0.0;
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    /** Coerce to String; returns {@code ""} for null. */
    public static String str(Object val) {
        return val != null ? val.toString() : "";
    }

    /** Coerce to BigDecimal; returns {@link BigDecimal#ZERO} for null or unparseable input. */
    public static BigDecimal toDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        try { return new BigDecimal(val.toString()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /**
     * Coerce to boolean; returns {@code false} for null.
     * Accepts {@link Boolean}, any {@link Number} (non-zero = true), and
     * strings {@code "1"} / {@code "true"} (case-insensitive).
     */
    public static boolean toBool(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).intValue() != 0;
        String s = val.toString().trim();
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    /**
     * Cast {@code val} to {@code Map<String, Object>}.
     * Returns an empty map when {@code val} is null or not a {@link Map}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> castMap(Object val) {
        if (val instanceof Map) return (Map<String, Object>) val;
        return new LinkedHashMap<>();
    }

    /** Return {@code a} if non-null, otherwise {@code b}. */
    public static <T> T coalesce(T a, T b) {
        return a != null ? a : b;
    }

    /** Coerce to Integer; returns {@code null} for null input. */
    public static Integer toIntOrNull(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Coerce to Long; returns {@code null} for null input. */
    public static Long toLongOrNull(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Coerce to String; returns {@code null} for null input. */
    public static String strOrNull(Object val) {
        return val != null ? val.toString() : null;
    }
}
