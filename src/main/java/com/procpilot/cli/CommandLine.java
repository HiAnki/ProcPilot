package com.procpilot.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal argument parser: {@code procpilot [--config FILE] <command> [--flag] [--opt value] [positional...]}.
 * Kept deliberately small instead of pulling in a CLI library.
 */
public final class CommandLine {

    private final String command;
    private final String configPath;
    private final List<String> positionals;
    private final Map<String, String> options;
    private final Set<String> flags;

    private CommandLine(String command, String configPath, List<String> positionals, Map<String, String> options,
                        Set<String> flags) {
        this.command = command;
        this.configPath = configPath;
        this.positionals = positionals;
        this.options = options;
        this.flags = flags;
    }

    /**
     * @param valueOptions option names (without dashes) that take a value, e.g. {@code "n"} for {@code -n 50}
     */
    public static CommandLine parse(String[] args, Set<String> valueOptions) throws UsageException {
        String command = null;
        String config = null;
        List<String> pos = new ArrayList<>();
        Map<String, String> opts = new HashMap<>();
        Set<String> flags = new HashSet<>();
        boolean endOfOptions = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!endOfOptions && a.equals("--")) {
                endOfOptions = true;
                continue;
            }
            if (!endOfOptions && (a.startsWith("-") && a.length() > 1)) {
                String name = a.replaceFirst("^-{1,2}", "");
                String inlineValue = null;
                int eq = name.indexOf('=');
                if (eq >= 0) {
                    inlineValue = name.substring(eq + 1);
                    name = name.substring(0, eq);
                }
                if (name.equals("config") || name.equals("c")) {
                    config = inlineValue != null ? inlineValue : requireValue(args, ++i, a);
                } else if (name.equals("h") || name.equals("help")) {
                    flags.add("help");
                } else if (valueOptions.contains(name)) {
                    opts.put(name, inlineValue != null ? inlineValue : requireValue(args, ++i, a));
                } else {
                    if (inlineValue != null) {
                        throw new UsageException("option " + a + " does not take a value");
                    }
                    flags.add(name);
                }
                continue;
            }
            if (command == null) {
                command = a;
            } else {
                pos.add(a);
            }
        }
        return new CommandLine(command, config, List.copyOf(pos), Map.copyOf(opts), Set.copyOf(flags));
    }

    private static String requireValue(String[] args, int i, String opt) throws UsageException {
        if (i >= args.length) {
            throw new UsageException("option " + opt + " requires a value");
        }
        return args[i];
    }

    public String command() {
        return command;
    }

    public String configPath() {
        return configPath;
    }

    public List<String> positionals() {
        return positionals;
    }

    public String option(String name) {
        return options.get(name);
    }

    public boolean flag(String name) {
        return flags.contains(name);
    }

    public Set<String> flags() {
        return flags;
    }

    public Set<String> optionNames() {
        return options.keySet();
    }

    public int intOption(String name, int def, int min, int max) throws UsageException {
        String v = options.get(name);
        if (v == null) {
            return def;
        }
        try {
            int i = Integer.parseInt(v);
            if (i < min || i > max) {
                throw new UsageException("--" + name + " must be between " + min + " and " + max);
            }
            return i;
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " expects an integer, got '" + v + "'");
        }
    }

    /** Thrown for invalid command-line usage; maps to exit code {@link ExitCodes#USAGE}. */
    public static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        public UsageException(String message) {
            super(message);
        }
    }
}
