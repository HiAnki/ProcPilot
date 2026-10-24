package com.procpilot.monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads process metrics from Linux {@code /proc}. Every metric is optional: anything that cannot be read
 * (process exited, permission denied, malformed data) is reported as "N/A (reason)" rather than invented.
 */
public final class ProcInspector {

    private static final long CLK_TCK = 100; // USER_HZ; 100 on all mainstream Linux builds

    private final Path procRoot;

    public ProcInspector() {
        this(Path.of("/proc"));
    }

    public ProcInspector(Path procRoot) {
        this.procRoot = procRoot;
    }

    /** Fields from /proc/[pid]/stat. comm may contain spaces and ')' so split at the LAST ')'. */
    public record Stat(String comm, char state, long ppid, long utime, long stime, long startTicks) {}

    public static Stat parseStat(String line) {
        int open = line.indexOf('(');
        int close = line.lastIndexOf(')');
        if (open < 0 || close < open) {
            throw new IllegalArgumentException("malformed stat line");
        }
        String comm = line.substring(open + 1, close);
        String[] f = line.substring(close + 2).trim().split("\\s+");
        // f[0]=state(3) f[1]=ppid(4) ... f[11]=utime(14) f[12]=stime(15) f[19]=starttime(22)
        if (f.length < 20) {
            throw new IllegalArgumentException("stat line has too few fields");
        }
        return new Stat(comm, f[0].charAt(0), Long.parseLong(f[1]), Long.parseLong(f[11]),
                Long.parseLong(f[12]), Long.parseLong(f[19]));
    }

    public Stat stat(long pid) throws IOException {
        return parseStat(Files.readString(procRoot.resolve(pid + "/stat"), StandardCharsets.UTF_8));
    }

    /** CPU % over the interval: delta(utime+stime) ticks / CLK_TCK / wall seconds. 100% = one full core. */
    public static double cpuPercent(long ticksBefore, long ticksAfter, long wallNanos) {
        if (wallNanos <= 0 || ticksAfter < ticksBefore) {
            return Double.NaN;
        }
        return (ticksAfter - ticksBefore) / (double) CLK_TCK / (wallNanos / 1e9) * 100.0;
    }

    public Map<String, String> inspect(long pid, long sampleMillis) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("PID", Long.toString(pid));
        Stat s1;
        try {
            s1 = stat(pid);
        } catch (IOException | RuntimeException e) {
            out.put("Error", "process " + pid + " not found or not readable (" + e.getClass().getSimpleName() + ")");
            return out;
        }
        out.put("PPID", Long.toString(s1.ppid()));
        out.put("Command", s1.comm());
        out.put("State", String.valueOf(s1.state()));
        Map<String, String> status = readStatus(pid);
        out.put("UID", status.getOrDefault("Uid", "N/A").split("\\s+")[0]);
        out.put("RSS", status.getOrDefault("VmRSS", "N/A (kernel thread or unreadable)"));
        out.put("VmSize", status.getOrDefault("VmSize", "N/A"));
        out.put("Threads", status.getOrDefault("Threads", "N/A"));
        out.put("Open FDs", fdCount(pid));
        out.put("Start time", ProcessHandle.of(pid).flatMap(h -> h.info().startInstant())
                .map(Object::toString).orElse("N/A"));
        long t0 = System.nanoTime();
        try {
            Thread.sleep(sampleMillis);
            Stat s2 = stat(pid);
            double cpu = cpuPercent(s1.utime() + s1.stime(), s2.utime() + s2.stime(), System.nanoTime() - t0);
            int cores = Runtime.getRuntime().availableProcessors();
            out.put("CPU", String.format("%.1f%% of one core (%.1f%% of %d cores, %dms sample)", cpu, cpu / cores,
                    cores, sampleMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.put("CPU", "N/A (interrupted)");
        } catch (IOException | RuntimeException e) {
            out.put("CPU", "N/A (process exited during sampling)");
        }
        out.put("Max open files", limit(pid, "Max open files"));
        out.put("Max processes", limit(pid, "Max processes"));
        return out;
    }

    private Map<String, String> readStatus(long pid) {
        Map<String, String> m = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(procRoot.resolve(pid + "/status"))) {
            lines.forEach(l -> {
                int i = l.indexOf(':');
                if (i > 0) {
                    m.put(l.substring(0, i), l.substring(i + 1).trim());
                }
            });
        } catch (IOException | RuntimeException e) {
            // leave empty; callers show N/A
        }
        return m;
    }

    private String fdCount(long pid) {
        try (Stream<Path> s = Files.list(procRoot.resolve(pid + "/fd"))) {
            return Long.toString(s.count());
        } catch (java.nio.file.AccessDeniedException e) {
            return "N/A (permission denied)";
        } catch (IOException | RuntimeException e) {
            return "N/A (" + e.getClass().getSimpleName() + ")";
        }
    }

    private String limit(long pid, String name) {
        try (Stream<String> lines = Files.lines(procRoot.resolve(pid + "/limits"))) {
            return lines.filter(l -> l.startsWith(name)).findFirst()
                    .map(l -> {
                        String[] f = l.substring(name.length()).trim().split("\\s+");
                        return "soft=" + f[0] + " hard=" + f[1];
                    }).orElse("N/A");
        } catch (IOException | RuntimeException e) {
            return "N/A";
        }
    }
}
