package com.procpilot.util;

import java.nio.file.Path;

/** Path helpers: home expansion and well-known ProcPilot locations (XDG-style, user-writable). */
public final class AppPaths {

    private AppPaths() {
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    /** Expands a leading {@code ~/} and resolves relative paths against {@code base}. */
    public static Path resolve(String raw, Path base) {
        String s = raw.trim();
        Path p;
        if (s.equals("~")) {
            p = home();
        } else if (s.startsWith("~/")) {
            p = home().resolve(s.substring(2));
        } else {
            p = Path.of(s);
        }
        if (!p.isAbsolute()) {
            p = base.resolve(p);
        }
        return p.normalize();
    }

    public static Path defaultConfigPath() {
        String env = System.getenv("PROCPILOT_CONFIG");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : home().resolve(".config");
        return base.resolve("procpilot").resolve("procpilot.yaml");
    }

    public static Path defaultStateDir() {
        String xdg = System.getenv("XDG_STATE_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : home().resolve(".local").resolve("state");
        return base.resolve("procpilot");
    }

    public static Path defaultRuntimeDir() {
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg).resolve("procpilot");
        }
        return defaultStateDir().resolve("run");
    }
}
