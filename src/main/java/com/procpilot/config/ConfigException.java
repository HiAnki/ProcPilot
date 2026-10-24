package com.procpilot.config;

import java.util.List;

/** Thrown when configuration cannot be loaded or fails validation. Carries every error found. */
public class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final List<String> errors;

    public ConfigException(List<String> errors) {
        super(errors.size() == 1 ? errors.get(0) : errors.size() + " configuration errors");
        this.errors = List.copyOf(errors);
    }

    public ConfigException(String error) {
        this(List.of(error));
    }

    public List<String> errors() {
        return errors;
    }
}
