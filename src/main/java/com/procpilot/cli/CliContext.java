package com.procpilot.cli;

import java.io.PrintStream;
import java.nio.file.Path;

import com.procpilot.config.ConfigException;
import com.procpilot.config.ConfigLoader;
import com.procpilot.config.ProcPilotConfig;
import com.procpilot.util.AppPaths;

/** Per-invocation state shared by CLI commands: output streams and lazily loaded configuration. */
public final class CliContext {

    private final PrintStream out;
    private final PrintStream err;
    private final Path configPath;
    private ProcPilotConfig config;

    public CliContext(PrintStream out, PrintStream err, String configOverride) {
        this.out = out;
        this.err = err;
        this.configPath = configOverride != null ? Path.of(configOverride) : AppPaths.defaultConfigPath();
    }

    public PrintStream out() {
        return out;
    }

    public PrintStream err() {
        return err;
    }

    public Path configPath() {
        return configPath;
    }

    public ProcPilotConfig config() throws ConfigException {
        if (config == null) {
            config = new ConfigLoader().load(configPath);
        }
        return config;
    }
}
