package com.procpilot.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class UtilTest {

    @Test
    void parsesDurations() {
        assertEquals(Duration.ofMillis(500), Durations.parse("500ms"));
        assertEquals(Duration.ofSeconds(5), Durations.parse("5s"));
        assertEquals(Duration.ofSeconds(5), Durations.parse("5"));
        assertEquals(Duration.ofMinutes(2), Durations.parse(" 2m "));
        assertEquals(Duration.ofHours(1), Durations.parse("1H"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("5 days"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("-1s"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("1.5s"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("99999999999999999999s"));
    }

    @Test
    void formatsDurations() {
        assertEquals("250ms", Durations.format(Duration.ofMillis(250)));
        assertEquals("3s", Durations.format(Duration.ofSeconds(3)));
        assertEquals("1.5s", Durations.format(Duration.ofMillis(1500)));
        assertEquals("2m5s", Durations.format(Duration.ofSeconds(125)));
        assertEquals("1h1m", Durations.format(Duration.ofSeconds(3660)));
        assertEquals("N/A", Durations.format(null));
    }

    @Test
    void parsesSizes() {
        assertEquals(100, Sizes.parse("100"));
        assertEquals(2048, Sizes.parse("2KB"));
        assertEquals(10L * 1024 * 1024, Sizes.parse("10mb"));
        assertEquals(1024L * 1024 * 1024, Sizes.parse("1G"));
        assertThrows(IllegalArgumentException.class, () -> Sizes.parse("ten"));
        assertThrows(ArithmeticException.class, () -> Sizes.parse("9999999999999GB"));
        assertEquals("1.5KB", Sizes.format(1536));
    }

    @Test
    void redactsSensitiveValues() {
        assertTrue(Redactor.isSensitiveName("DB_PASSWORD", Set.of()));
        assertTrue(Redactor.isSensitiveName("api_token", Set.of()));
        assertTrue(Redactor.isSensitiveName("PLAIN", Set.of("PLAIN")));
        assertFalse(Redactor.isSensitiveName("PORT", Set.of()));
        assertEquals(Redactor.MASK, Redactor.envValueForDisplay("AWS_SECRET_ACCESS_KEY", "abc", Set.of()));
        assertEquals("8080", Redactor.envValueForDisplay("PORT", "8080", Set.of()));
    }

    @Test
    void hidesCommandArgumentsByDefault() {
        List<String> argv = List.of("/usr/bin/app", "--password=hunter2", "--port", "80");
        assertEquals("/usr/bin/app [3 arg(s) hidden; use --show-args]", Redactor.commandForDisplay(argv, false));
        assertEquals("/usr/bin/app --password=****** --port 80", Redactor.commandForDisplay(argv, true));
        assertEquals("/bin/true", Redactor.commandForDisplay(List.of("/bin/true"), false));
        assertEquals("N/A", Redactor.commandForDisplay(List.of(), false));
    }
}
