package com.procpilot.config;

import java.util.Locale;

public enum RestartPolicyType {
    NEVER("never"),
    ON_FAILURE("on-failure"),
    ALWAYS("always");

    private final String configName;

    RestartPolicyType(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static RestartPolicyType fromConfig(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (RestartPolicyType t : values()) {
            if (t.configName.equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown restart policy '" + value + "' (expected never, on-failure or always)");
    }
}
