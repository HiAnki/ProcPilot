package com.procpilot.core;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.procpilot.config.RestartConfig;
import com.procpilot.config.RestartPolicyType;
import com.procpilot.config.ServiceConfig;

/**
 * Starts services as child processes, restarts them according to their policy, and stops them gracefully.
 *
 * <p>Concurrency: each {@link Managed} is guarded by its own monitor. Every launch increments a generation
 * counter, and exit callbacks carry the generation they were registered for, so a late exit notification
 * from an old process can never affect a newer one. An intentional stop sets {@code stopRequested}, which
 * suppresses restarts and cancels any pending restart task.
 */
public final class Supervisor implements AutoCloseable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Map<String, Managed> services = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "procpilot-restart");
        t.setDaemon(true);
        return t;
    });
    private final PrintStream events;

    public Supervisor(List<ServiceConfig> configs, PrintStream events) {
        this.events = events;
        for (ServiceConfig c : configs) {
            services.put(c.name(), new Managed(c));
        }
    }

    public void startAll() {
        services.values().forEach(this::start);
    }

    public Managed get(String name) {
        return services.get(name);
    }

    public List<Managed> all() {
        return List.copyOf(services.values());
    }

    private void log(String service, String msg) {
        synchronized (events) {
            events.println(LocalTime.now().format(TS) + " [" + service + "] " + msg);
        }
    }

    void start(Managed m) {
        synchronized (m) {
            if (m.stopRequested || m.state == ServiceState.RUNNING || m.state == ServiceState.STARTING) {
                return;
            }
            m.state = ServiceState.STARTING;
            ServiceConfig c = m.config;
            try {
                Files.createDirectories(c.stdoutLog().getParent());
                Files.createDirectories(c.stderrLog().getParent());
                ProcessBuilder pb = new ProcessBuilder(c.command())
                        .directory(c.workingDir().toFile())
                        .redirectInput(Redirect.from(new java.io.File("/dev/null")))
                        // Redirect straight to files: no pipes that can fill up and block the child.
                        .redirectOutput(Redirect.appendTo(c.stdoutLog().toFile()))
                        .redirectError(Redirect.appendTo(c.stderrLog().toFile()));
                pb.environment().putAll(c.env());
                Process p = pb.start();
                long gen = ++m.generation;
                m.process = p;
                m.startedAt = Instant.now();
                m.state = ServiceState.RUNNING;
                log(c.name(), "started pid=" + p.pid() + (m.restarts > 0 ? " (restart #" + m.restarts + ")" : ""));
                p.onExit().thenAccept(ph -> onExit(m, gen, ph));
            } catch (IOException e) {
                m.state = ServiceState.FAILED;
                m.lastError = e.getMessage();
                log(c.name(), "FAILED to start: " + e.getMessage());
            }
        }
    }

    private void onExit(Managed m, long gen, Process p) {
        synchronized (m) {
            if (gen != m.generation) {
                return; // stale notification from an older process
            }
            int code = p.exitValue();
            m.lastExitCode = code;
            Duration ran = Duration.between(m.startedAt, Instant.now());
            if (m.stopRequested) {
                m.state = ServiceState.STOPPED;
                log(m.config.name(), "stopped (exit " + code + ")");
                m.notifyAll();
                return;
            }
            log(m.config.name(), "exited unexpectedly with code " + code + " after " + ran.toMillis() + "ms");
            RestartConfig rc = m.config.restart();
            if (ran.compareTo(rc.resetAfter()) >= 0) {
                m.consecutiveFailures = 0;
            }
            boolean wantRestart = rc.policy() == RestartPolicyType.ALWAYS
                    || (rc.policy() == RestartPolicyType.ON_FAILURE && code != 0);
            if (!wantRestart) {
                m.state = code == 0 ? ServiceState.STOPPED : ServiceState.FAILED;
                log(m.config.name(), "not restarting (policy " + rc.policy().configName() + ")");
                m.notifyAll();
                return;
            }
            if (m.consecutiveFailures >= rc.maxAttempts()) {
                m.state = ServiceState.FAILED;
                log(m.config.name(), "giving up after " + rc.maxAttempts() + " restart attempts (crash loop)");
                m.notifyAll();
                return;
            }
            Duration delay = backoff(rc, m.consecutiveFailures);
            m.consecutiveFailures++;
            m.restarts++;
            m.state = ServiceState.BACKOFF;
            log(m.config.name(), "restarting in " + delay.toMillis() + "ms (attempt " + m.consecutiveFailures
                    + "/" + rc.maxAttempts() + ")");
            m.pendingRestart = scheduler.schedule(() -> {
                synchronized (m) {
                    m.pendingRestart = null;
                    if (m.state != ServiceState.BACKOFF) {
                        return;
                    }
                    m.state = ServiceState.STOPPED;
                }
                start(m);
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
            m.notifyAll();
        }
    }

    /** Exponential backoff: initialDelay * multiplier^failures, capped at maxDelay. */
    public static Duration backoff(RestartConfig rc, int failures) {
        double ms = rc.initialDelay().toMillis() * Math.pow(rc.multiplier(), failures);
        return Duration.ofMillis((long) Math.min(ms, rc.maxDelay().toMillis()));
    }

    /** Graceful stop: SIGTERM to descendants and the process, then SIGKILL after the stop timeout. */
    public void stop(Managed m) {
        Process p;
        synchronized (m) {
            m.stopRequested = true;
            if (m.pendingRestart != null) {
                m.pendingRestart.cancel(false);
                m.pendingRestart = null;
            }
            if (m.state != ServiceState.RUNNING) {
                if (m.state == ServiceState.BACKOFF) {
                    m.state = ServiceState.STOPPED;
                    log(m.config.name(), "pending restart cancelled by stop request");
                }
                return; // already stopped/stopping: repeated stops are harmless
            }
            m.state = ServiceState.STOPPING;
            p = m.process;
        }
        List<ProcessHandle> tree = new ArrayList<>(p.descendants().toList());
        tree.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        log(m.config.name(), "stopping pid=" + p.pid() + " (SIGTERM, timeout " + m.config.stopTimeout().toMillis() + "ms)");
        tree.forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            p.onExit().get(m.config.stopTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log(m.config.name(), "did not exit within timeout; sending SIGKILL");
            tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.onExit().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        } catch (Exception e) {
            p.destroyForcibly();
        }
        synchronized (m) {
            while (m.state == ServiceState.STOPPING) {
                try {
                    m.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void stopAll() {
        // Stop concurrently so total shutdown time is bounded by the longest stopTimeout, not the sum.
        List<Thread> threads = new ArrayList<>();
        for (Managed m : services.values()) {
            threads.add(Thread.ofPlatform().start(() -> stop(m)));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stopAll();
        scheduler.shutdownNow();
    }

    /** Mutable runtime state for one service; all fields guarded by the instance monitor. */
    public static final class Managed {
        final ServiceConfig config;
        ServiceState state = ServiceState.STOPPED;
        Process process;
        Instant startedAt;
        Integer lastExitCode;
        String lastError;
        long generation;
        int consecutiveFailures;
        int restarts;
        boolean stopRequested;
        ScheduledFuture<?> pendingRestart;

        Managed(ServiceConfig config) {
            this.config = config;
        }

        public synchronized ServiceState state() {
            return state;
        }

        public synchronized long pid() {
            return process == null ? -1 : process.pid();
        }

        public synchronized int restarts() {
            return restarts;
        }

        public synchronized Integer lastExitCode() {
            return lastExitCode;
        }

        public synchronized String summary() {
            return String.format("%-16s %-9s pid=%-7s restarts=%-3d lastExit=%s", config.name(), state,
                    process != null && state == ServiceState.RUNNING ? process.pid() : "-", restarts,
                    lastExitCode == null ? "-" : lastExitCode);
        }

        /** Waits (bounded) until the predicate holds. Returns whether it did. */
        public synchronized boolean awaitState(java.util.function.Predicate<Managed> cond, Duration timeout)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!cond.test(this)) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    return false;
                }
                wait(Math.min(left, 100));
            }
            return true;
        }
    }
}
