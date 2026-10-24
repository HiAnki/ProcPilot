package com.procpilot.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.procpilot.config.ProcPilotConfig;
import com.procpilot.config.ServiceConfig;
import com.procpilot.core.Supervisor;
import com.procpilot.monitor.ProcInspector;

/** {@code run} (foreground supervisor) and {@code inspect <pid>}. */
final class RunCommands {

    private RunCommands() {
    }

    static final class Run implements CliCommand {
        @Override
        public String name() {
            return "run";
        }

        @Override
        public String usage() {
            return "run [service...]";
        }

        @Override
        public String summary() {
            return "Supervise services in the foreground (Ctrl-C stops them gracefully)";
        }

        @Override
        public java.util.Set<String> valueOptions() {
            return java.util.Set.of("status-interval");
        }

        @Override
        public int run(CommandLine cl, CliContext ctx) throws Exception {
            ProcPilotConfig cfg = ctx.config();
            List<ServiceConfig> selected = new ArrayList<>();
            if (cl.positionals().isEmpty()) {
                selected.addAll(cfg.services().values());
            } else {
                for (String n : cl.positionals()) {
                    ServiceConfig s = cfg.service(n);
                    if (s == null) {
                        throw new CliFailure(ExitCodes.NOT_FOUND, "unknown service '" + n + "'");
                    }
                    selected.add(s);
                }
            }
            int statusEvery = cl.intOption("status-interval", 30, 1, 86400);
            Supervisor sup = new Supervisor(selected, ctx.out());
            CountDownLatch done = new CountDownLatch(1);
            Thread hook = new Thread(() -> {
                ctx.out().println("procpilot: shutdown requested, stopping services...");
                sup.close();
                done.countDown();
            }, "procpilot-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            sup.startAll();
            while (!done.await(statusEvery, java.util.concurrent.TimeUnit.SECONDS)) {
                ctx.out().println("--- status ---");
                sup.all().forEach(m -> ctx.out().println(m.summary()));
            }
            return ExitCodes.OK;
        }
    }

    static final class Inspect implements CliCommand {
        @Override
        public String name() {
            return "inspect";
        }

        @Override
        public String usage() {
            return "inspect <pid>";
        }

        @Override
        public String summary() {
            return "Show /proc metrics for a process (read-only)";
        }

        @Override
        public int run(CommandLine cl, CliContext ctx) throws Exception {
            if (cl.positionals().size() != 1) {
                throw new CommandLine.UsageException("inspect requires exactly one PID");
            }
            long pid;
            try {
                pid = Long.parseLong(cl.positionals().get(0));
                if (pid <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new CommandLine.UsageException("invalid PID '" + cl.positionals().get(0) + "'");
            }
            Map<String, String> info = new ProcInspector().inspect(pid, 500);
            info.forEach((k, v) -> ctx.out().printf("%-15s %s%n", k + ":", v));
            return info.containsKey("Error") ? ExitCodes.NOT_FOUND : ExitCodes.OK;
        }
    }
}
