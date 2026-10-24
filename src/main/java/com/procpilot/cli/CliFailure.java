package com.procpilot.cli;

/** A user-facing failure with a specific exit code. The message is printed without a stack trace. */
public class CliFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final int exitCode;

    public CliFailure(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
