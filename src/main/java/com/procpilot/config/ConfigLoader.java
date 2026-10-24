package com.procpilot.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads a YAML configuration file and hands the raw tree to {@link ConfigValidator}.
 *
 * <p>Uses SnakeYAML's {@link SafeConstructor}, so only plain maps, lists and scalars can be produced;
 * YAML tags that would instantiate arbitrary Java classes are rejected. Duplicate keys are rejected
 * (which also guarantees unique service names), and file size / alias expansion are bounded.
 */
public final class ConfigLoader {

    static final long MAX_CONFIG_BYTES = 1024 * 1024;

    private final ConfigValidator validator;

    public ConfigLoader() {
        this(new ConfigValidator());
    }

    public ConfigLoader(ConfigValidator validator) {
        this.validator = validator;
    }

    public ProcPilotConfig load(Path file) throws ConfigException {
        Path abs = file.toAbsolutePath().normalize();
        String text;
        try {
            if (Files.isDirectory(abs)) {
                throw new ConfigException("config path " + abs + " is a directory, expected a YAML file");
            }
            long size = Files.size(abs);
            if (size > MAX_CONFIG_BYTES) {
                throw new ConfigException("config file " + abs + " is too large (" + size + " bytes, limit "
                        + MAX_CONFIG_BYTES + ")");
            }
            text = Files.readString(abs, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new ConfigException("config file not found: " + abs
                    + " (create one from examples/procpilot.yaml or pass --config <file>)");
        } catch (IOException e) {
            throw new ConfigException("cannot read config file " + abs + ": " + e.getMessage());
        }
        Map<String, Object> raw = parse(text, abs.toString());
        return validator.validate(raw, abs);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String text, String origin) throws ConfigException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setAllowRecursiveKeys(false);
        options.setCodePointLimit((int) MAX_CONFIG_BYTES);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object doc;
        try {
            doc = yaml.load(text);
        } catch (YAMLException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage().strip();
            throw new ConfigException("malformed YAML in " + origin + ": " + msg);
        }
        if (doc == null) {
            throw new ConfigException("config file " + origin + " is empty");
        }
        if (!(doc instanceof Map)) {
            throw new ConfigException("config file " + origin + " must contain a mapping at the top level");
        }
        return (Map<String, Object>) doc;
    }
}
