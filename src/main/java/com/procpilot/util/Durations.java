package com.procpilot.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats human-readable durations such as {@code 500ms}, {@code 5s}, {@code 2m}, {@code 1h}.
 * A bare integer is interpreted as seconds.
 */
public final class Durations {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\s*(ms|s|m|h)?$");

    private Durations() {
    }

    public static Duration parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("duration is null");
        }
        Matcher m = PATTERN.matcher(text.trim().toLowerCase());
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid duration '" + text + "' (expected e.g. 500ms, 5s, 2m, 1h)");
        }
        long value;
        try {
            value = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("duration out of range: '" + text + "'");
        }
        String unit = m.group(2) == null ? "s" : m.group(2);
        return switch (unit) {
            case "ms" -> Duration.ofMillis(value);
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            case "h" -> Duration.ofHours(value);
            default -> throw new IllegalArgumentException("invalid duration unit in '" + text + "'");
        };
    }

    public static String format(Duration d) {
        if (d == null) {
            return "N/A";
        }
        long ms = d.toMillis();
        if (ms < 1000) {
            return ms + "ms";
        }
        long s = d.toSeconds();
        if (s < 60) {
            return (ms % 1000 == 0) ? s + "s" : String.format("%.1fs", ms / 1000.0);
        }
        if (s < 3600) {
            return (s / 60) + "m" + (s % 60) + "s";
        }
        long h = s / 3600;
        if (h < 48) {
            return h + "h" + ((s % 3600) / 60) + "m";
        }
        return (h / 24) + "d" + (h % 24) + "h";
    }
}
