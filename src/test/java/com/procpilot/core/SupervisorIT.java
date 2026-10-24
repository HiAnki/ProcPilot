package com.procpilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.procpilot.config.RestartConfig;
import com.procpilot.config.RestartPolicyType;
import com.procpilot.config.RotationConfig;
import com.procpilot.config.ServiceConfig;
import com.procpilot.monitor.ProcInspector;

class SupervisorIT {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private final ByteArrayOutputStream events = new ByteArrayOutputStream();

    private ServiceConfig svc(String name, String script, RestartPolicyType policy, int maxAttempts,
                              Duration stopTimeout) {
        RestartConfig rc = new RestartConfig(policy, maxAttempts, Duration.ofMillis(50), Duration.ofMillis(200), 2.0,
                Duration.ofSeconds(60));
        return new ServiceConfig(name, List.of("/bin/sh", "-c", script), dir, Map.of("GREETING", "hello"), Set.of(),
                dir.resolve(name + ".out"), dir.resolve(name + ".err"), rc, stopTimeout, null, null,
                RotationConfig.defaults());
    }

    private Supervisor sup(ServiceConfig... s) {
        return new Supervisor(List.of(s), new PrintStream(events, true));
    }

    @Test
    void startsCapturesOutputAndStopsGracefully() throws Exception {
        try (Supervisor sup = sup(svc("echo", "echo $GREETING; echo oops >&2; exec sleep 30",
                RestartPolicyType.ON_FAILURE, 3, Duration.ofSeconds(5)))) {
            sup.startAll();
            Supervisor.Managed m = sup.get("echo");
            assertEquals(ServiceState.RUNNING, m.state());
            long pid = m.pid();
            assertTrue(ProcessHandle.of(pid).isPresent());
            assertTrue(poll(() -> read("echo.out").contains("hello")));
            assertTrue(poll(() -> read("echo.err").contains("oops")));

            sup.stop(m);
            assertEquals(ServiceState.STOPPED, m.state());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            sup.stop(m); // repeated stop is a no-op
            assertEquals(ServiceState.STOPPED, m.state());
            assertEquals(0, m.restarts(), "intentional stop must not trigger a restart");
        }
    }

    @Test
    void restartsCrashingServiceUntilMaxAttempts() throws Exception {
        try (Supervisor sup = sup(svc("crashy", "echo run; exit 3", RestartPolicyType.ON_FAILURE, 3,
                Duration.ofSeconds(1)))) {
            sup.startAll();
            Supervisor.Managed m = sup.get("crashy");
            assertTrue(m.awaitState(x -> x.state == ServiceState.FAILED, WAIT));
            assertEquals(3, m.restarts());
            assertEquals(3, m.lastExitCode());
            assertTrue(poll(() -> read("crashy.out").lines().count() == 4)); // initial run + 3 restarts
            assertTrue(events.toString().contains("giving up after 3 restart attempts"));
        }
    }

    @Test
    void neverPolicyDoesNotRestart() throws Exception {
        try (Supervisor sup = sup(svc("once", "exit 1", RestartPolicyType.NEVER, 5, Duration.ofSeconds(1)))) {
            sup.startAll();
            Supervisor.Managed m = sup.get("once");
            assertTrue(m.awaitState(x -> x.state == ServiceState.FAILED, WAIT));
            assertEquals(0, m.restarts());
        }
    }

    @Test
    void forceKillsChildThatIgnoresSigterm() throws Exception {
        try (Supervisor sup = sup(svc("stubborn", "trap '' TERM; echo ready; while true; do sleep 0.1; done",
                RestartPolicyType.ALWAYS, 3, Duration.ofMillis(500)))) {
            sup.startAll();
            Supervisor.Managed m = sup.get("stubborn");
            long pid = m.pid();
            assertTrue(poll(() -> read("stubborn.out").contains("ready")));
            sup.stop(m);
            assertEquals(ServiceState.STOPPED, m.state());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(events.toString().contains("sending SIGKILL"));
            assertEquals(0, m.restarts());
        }
    }

    @Test
    void cleanupDoesNotTouchUnrelatedProcesses() throws Exception {
        Process unrelated = new ProcessBuilder("sleep", "30").start();
        try {
            try (Supervisor sup = sup(svc("a", "exec sleep 30", RestartPolicyType.ALWAYS, 3, Duration.ofSeconds(2)))) {
                sup.startAll();
            }
            assertTrue(unrelated.isAlive());
        } finally {
            unrelated.destroyForcibly();
        }
    }

    @Test
    void inspectHandlesProcessExitingDuringSampling() throws Exception {
        Process p = new ProcessBuilder("sleep", "0.2").start();
        Map<String, String> info = new ProcInspector().inspect(p.pid(), 600);
        p.waitFor();
        assertTrue(info.get("CPU").startsWith("N/A"), info.toString());
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        RestartConfig rc = new RestartConfig(RestartPolicyType.ALWAYS, 10, Duration.ofMillis(100),
                Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60));
        assertEquals(100, Supervisor.backoff(rc, 0).toMillis());
        assertEquals(200, Supervisor.backoff(rc, 1).toMillis());
        assertEquals(800, Supervisor.backoff(rc, 3).toMillis());
        assertEquals(1000, Supervisor.backoff(rc, 10).toMillis());
    }

    private String read(String f) {
        try {
            return Files.readString(dir.resolve(f));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean poll(java.util.function.BooleanSupplier c) throws InterruptedException {
        long end = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
