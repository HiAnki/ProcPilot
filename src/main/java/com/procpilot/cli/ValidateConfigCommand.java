package com.procpilot.cli;

import java.io.PrintStream;
import java.util.Map;

import com.procpilot.config.ProcPilotConfig;
import com.procpilot.config.ServiceConfig;
import com.procpilot.util.Durations;
import com.procpilot.util.Redactor;
import com.procpilot.util.Sizes;

final class ValidateConfigCommand implements CliCommand {

    @Override
    public String name() {
        return "validate-config";
    }

    @Override
    public String usage() {
        return "validate-config [--verbose]";
    }

    @Override
    public String summary() {
        return "Validate the configuration file and print the resolved services";
    }

    @Override
    public int run(CommandLine cl, CliContext ctx) throws Exception {
        // ConfigException propagates to Main, which prints every error and exits with CONFIG.
        ProcPilotConfig cfg = ctx.config();
        PrintStream out = ctx.out();
        out.println("Configuration OK: " + cfg.source());
        out.println("  log dir:     " + cfg.logDir());
        out.println("  runtime dir: " + cfg.runtimeDir());
        out.println("  services:    " + cfg.services().size());
        for (ServiceConfig s : cfg.services().values()) {
            out.println();
            out.println("  [" + s.name() + "]");
            out.println("    command:      " + Redactor.commandForDisplay(s.command(), cl.flag("verbose")));
            out.println("    workingDir:   " + s.workingDir());
            out.println("    restart:      " + s.restart().policy().configName()
                    + " (maxAttempts=" + s.restart().maxAttempts()
                    + ", backoff " + Durations.format(s.restart().initialDelay()) + " x" + s.restart().multiplier()
                    + " up to " + Durations.format(s.restart().maxDelay())
                    + ", reset after " + Durations.format(s.restart().resetAfter()) + ")");
            out.println("    stopTimeout:  " + Durations.format(s.stopTimeout()));
            out.println("    stdout:       " + s.stdoutLog());
            out.println("    stderr:       " + s.stderrLog());
            out.println("    logRotation:  " + Sizes.format(s.logRotation().maxBytes()) + ", keep "
                    + s.logRotation().keep());
            if (s.healthCheck() != null) {
                out.println("    healthCheck:  " + s.healthCheck().describe() + " every "
                        + Durations.format(s.healthCheck().interval()));
            }
            if (!s.env().isEmpty()) {
                out.println("    env:");
                for (Map.Entry<String, String> e : s.env().entrySet()) {
                    String shown = cl.flag("verbose")
                            ? Redactor.envValueForDisplay(e.getKey(), e.getValue(), s.secretEnv())
                            : "<set>";
                    out.println("      " + e.getKey() + "=" + shown);
                }
            }
        }
        if (!cfg.warnings().isEmpty()) {
            out.println();
            out.println("Warnings:");
            for (String w : cfg.warnings()) {
                out.println("  - " + w);
            }
        }
        return ExitCodes.OK;
    }
}
