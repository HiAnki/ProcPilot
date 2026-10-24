package com.procpilot.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build version, read from a Maven-filtered resource. */
public final class Version {

    private static final String VERSION = load();

    private Version() {
    }

    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/procpilot-version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "unknown");
            return v.startsWith("${") ? "dev" : v;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
