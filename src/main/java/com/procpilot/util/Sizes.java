package com.procpilot.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats byte sizes. Units are binary (1KB = 1024 bytes). */
public final class Sizes {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\s*(b|kb|k|mb|m|gb|g)?$");

    private Sizes() {
    }

    public static long parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("size is null");
        }
        Matcher m = PATTERN.matcher(text.trim().toLowerCase());
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid size '" + text + "' (expected e.g. 512KB, 10MB, 1GB)");
        }
        long value;
        try {
            value = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("size out of range: '" + text + "'");
        }
        String unit = m.group(2) == null ? "b" : m.group(2);
        long multiplier = switch (unit) {
            case "b" -> 1L;
            case "k", "kb" -> 1024L;
            case "m", "mb" -> 1024L * 1024;
            case "g", "gb" -> 1024L * 1024 * 1024;
            default -> throw new IllegalArgumentException("invalid size unit in '" + text + "'");
        };
        return Math.multiplyExact(value, multiplier);
    }

    public static String format(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1fKB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1fMB", mb);
        }
        return String.format("%.2fGB", mb / 1024.0);
    }
}
