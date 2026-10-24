package com.procpilot.cli;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.procpilot.config.ConfigException;

/** Entry point for the {@code procpilot} command. */
public final class Main {

    private final Map<String, CliCommand> commands = new LinkedHashMap<>();

    public Main() {
        register(new RunCommands.Run());
        register(new RunCommands.Inspect());
        register(new ValidateConfigCommand());
        register(new SimpleCommand("version", "version", "Print the ProcPilot version", (cl, ctx) -> {
            ctx.out().println("procpilot " + Version.get() + " (Java " + Runtime.version() + ", "
                    + System.getProperty("os.name") + " " + System.getProperty("os.version") + ")");
            return ExitCodes.OK;
        }));
        register(new SimpleCommand("help", "help [command]", "Show usage information", (cl, ctx) -> {
            printHelp(ctx.out());
            return ExitCodes.OK;
        }));
    }

    void register(CliCommand c) {
        commands.put(c.name(), c);
    }

    public static void main(String[] args) {
        int code = new Main().run(args, System.out, System.err);
        System.out.flush();
        System.exit(code);
    }

    public int run(String[] args, PrintStream out, PrintStream err) {
        // First pass only to find the command name, so its own value options are known for the real parse.
        CommandLine cl;
        try {
            CommandLine probe = CommandLine.parse(args, allValueOptions());
            CliCommand cmd = probe.command() == null ? null : commands.get(probe.command());
            if (probe.command() == null) {
                printHelp(probe.flag("help") ? out : err);
                return probe.flag("help") ? ExitCodes.OK : ExitCodes.USAGE;
            }
            if (cmd == null) {
                err.println("procpilot: unknown command '" + probe.command() + "'");
                err.println("Run 'procpilot help' for usage.");
                return ExitCodes.USAGE;
            }
            cl = CommandLine.parse(args, cmd.valueOptions());
            for (String opt : cl.optionNames()) {
                if (!cmd.valueOptions().contains(opt)) {
                    throw new CommandLine.UsageException("unknown option --" + opt + " for '" + cmd.name() + "'");
                }
            }
            if (cl.flag("help")) {
                out.println("Usage: procpilot " + cmd.usage());
                out.println("  " + cmd.summary());
                return ExitCodes.OK;
            }
            CliContext ctx = new CliContext(out, err, cl.configPath());
            return cmd.run(cl, ctx);
        } catch (CommandLine.UsageException e) {
            err.println("procpilot: " + e.getMessage());
            err.println("Run 'procpilot help' for usage.");
            return ExitCodes.USAGE;
        } catch (ConfigException e) {
            err.println("procpilot: invalid configuration:");
            for (String msg : e.errors()) {
                err.println("  - " + msg);
            }
            return ExitCodes.CONFIG;
        } catch (CliFailure e) {
            err.println("procpilot: " + e.getMessage());
            return e.exitCode();
        } catch (Exception e) {
            err.println("procpilot: unexpected error: " + e);
            return ExitCodes.FAILURE;
        }
    }

    private Set<String> allValueOptions() {
        java.util.HashSet<String> all = new java.util.HashSet<>();
        commands.values().forEach(c -> all.addAll(c.valueOptions()));
        return all;
    }

    private void printHelp(PrintStream out) {
        out.println("ProcPilot " + Version.get() + " - lightweight Linux process supervisor and resource monitor");
        out.println();
        out.println("Usage: procpilot [--config FILE] <command> [options]");
        out.println();
        out.println("Commands:");
        int width = commands.values().stream().mapToInt(c -> c.usage().length()).max().orElse(20);
        for (CliCommand c : commands.values()) {
            out.printf("  %-" + width + "s  %s%n", c.usage(), c.summary());
        }
        out.println();
        out.println("Global options:");
        out.println("  --config FILE   configuration file (default: $PROCPILOT_CONFIG or ~/.config/procpilot/procpilot.yaml)");
        out.println("  -h, --help      show help for a command");
        out.println();
        out.println("Exit codes: 0 ok, 1 failure, 2 usage, 3 config invalid, 4 daemon unavailable,");
        out.println("            5 not found, 6 state conflict, 7 service not running");
    }

    /** Functional command for trivial cases. */
    record SimpleCommand(String name, String usage, String summary, Handler handler) implements CliCommand {
        interface Handler {
            int run(CommandLine cl, CliContext ctx) throws Exception;
        }

        @Override
        public int run(CommandLine cl, CliContext ctx) throws Exception {
            return handler.run(cl, ctx);
        }
    }

    /** Lists commands in registration order; used by tests. */
    List<String> commandNames() {
        return List.copyOf(commands.keySet());
    }
}
