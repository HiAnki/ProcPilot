package com.procpilot.cli;

/** Process exit codes returned by the {@code procpilot} CLI. Documented in the README. */
public final class ExitCodes {

    public static final int OK = 0;
    /** Unexpected failure (I/O error, internal error, operation failed). */
    public static final int FAILURE = 1;
    /** Invalid command-line usage. */
    public static final int USAGE = 2;
    /** Configuration file missing, malformed or invalid. */
    public static final int CONFIG = 3;
    /** The supervisor daemon is not running or not reachable. */
    public static final int DAEMON_UNAVAILABLE = 4;
    /** Unknown service name or PID. */
    public static final int NOT_FOUND = 5;
    /** Operation conflicts with current state (e.g. starting a running service). */
    public static final int CONFLICT = 6;
    /** {@code status <service>}: the service is not running (STOPPED/FAILED/BACKOFF). */
    public static final int NOT_RUNNING = 7;

    private ExitCodes() {
    }
}
