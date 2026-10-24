package com.procpilot.cli;

import java.util.Set;

/** A single CLI sub-command. */
public interface CliCommand {

    String name();

    /** One-line usage shown in {@code procpilot help}. */
    String usage();

    String summary();

    /** Option names that take a value (e.g. {@code n} for {@code -n 50}). */
    default Set<String> valueOptions() {
        return Set.of();
    }

    int run(CommandLine cl, CliContext ctx) throws Exception;
}
