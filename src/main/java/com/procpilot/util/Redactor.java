package com.procpilot.util;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which values must never be printed or logged.
 * Environment variables whose names look secret, or which are explicitly listed as secret,
 * are always masked. Command-line arguments are hidden unless the caller opts in.
 */
public final class Redactor {

    public static final String MASK = "******";

    private static final Pattern SENSITIVE_NAME =
            Pattern.compile("(?i).*(pass|secret|token|key|credential|auth|cookie|session|private).*");

    private Redactor() {
    }

    public static boolean isSensitiveName(String name, Set<String> explicitSecrets) {
        return explicitSecrets.contains(name) || SENSITIVE_NAME.matcher(name).matches();
    }

    public static String envValueForDisplay(String name, String value, Set<String> explicitSecrets) {
        return isSensitiveName(name, explicitSecrets) ? MASK : value;
    }

    /**
     * Returns a display form of a command line. By default only the executable is shown and
     * the number of hidden arguments is indicated, because arguments frequently contain secrets.
     */
    public static String commandForDisplay(List<String> argv, boolean showArgs) {
        if (argv == null || argv.isEmpty()) {
            return "N/A";
        }
        if (showArgs) {
            StringBuilder sb = new StringBuilder();
            for (String a : argv) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(maskInlineSecret(a));
            }
            return sb.toString();
        }
        int hidden = argv.size() - 1;
        return hidden == 0 ? argv.get(0) : argv.get(0) + " [" + hidden + " arg(s) hidden; use --show-args]";
    }

    /** Masks the value part of arguments like {@code --password=hunter2} or {@code TOKEN=abc}. */
    public static String maskInlineSecret(String arg) {
        int eq = arg.indexOf('=');
        if (eq > 0 && SENSITIVE_NAME.matcher(arg.substring(0, eq)).matches()) {
            return arg.substring(0, eq + 1) + MASK;
        }
        return arg;
    }
}
