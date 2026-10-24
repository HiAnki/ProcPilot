package com.procpilot.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcInspectorTest {

    private static final String STAT =
            "1234 (my (weird) proc) S 1 1234 1234 0 -1 4194304 100 0 0 0 250 50 0 0 20 0 1 0 98765 1000 200 "
                    + "18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0";

    @Test
    void parsesStatWithParensInComm() {
        ProcInspector.Stat s = ProcInspector.parseStat(STAT);
        assertEquals("my (weird) proc", s.comm());
        assertEquals('S', s.state());
        assertEquals(1, s.ppid());
        assertEquals(250, s.utime());
        assertEquals(50, s.stime());
        assertEquals(98765, s.startTicks());
    }

    @Test
    void rejectsMalformedStat() {
        assertThrows(IllegalArgumentException.class, () -> ProcInspector.parseStat("garbage"));
        assertThrows(IllegalArgumentException.class, () -> ProcInspector.parseStat("1 (x) S 1 2"));
    }

    @Test
    void computesCpuFromDeltas() {
        // 50 ticks over 1s at 100 ticks/s = 0.5 core = 50%
        assertEquals(50.0, ProcInspector.cpuPercent(100, 150, 1_000_000_000L), 1e-9);
        assertEquals(200.0, ProcInspector.cpuPercent(0, 100, 500_000_000L), 1e-9); // 2 cores busy
        assertTrue(Double.isNaN(ProcInspector.cpuPercent(10, 5, 1_000_000_000L)));
    }

    @Test
    void readsFixtureAndReportsMissingMetricsAsNA(@TempDir Path root) throws Exception {
        Path p = Files.createDirectories(root.resolve("1234"));
        Files.writeString(p.resolve("stat"), STAT);
        Files.writeString(p.resolve("status"), "Name:\tx\nUid:\t1000\t1000\t1000\t1000\nVmRSS:\t  2048 kB\n");
        Map<String, String> info = new ProcInspector(root).inspect(1234, 10);
        assertEquals("1", info.get("PPID"));
        assertEquals("2048 kB", info.get("RSS"));
        assertEquals("1000", info.get("UID"));
        assertEquals("N/A", info.get("VmSize"));
        assertTrue(info.get("Open FDs").startsWith("N/A"));
        assertEquals("N/A", info.get("Max open files"));
    }

    @Test
    void missingProcessIsReported(@TempDir Path root) {
        Map<String, String> info = new ProcInspector(root).inspect(99999, 10);
        assertTrue(info.containsKey("Error"));
    }
}
