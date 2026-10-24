package com.procpilot.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level validated configuration.
 *
 * @param source         file the configuration was loaded from
 * @param logDir         directory for supervisor and default service logs
 * @param runtimeDir     directory holding the control socket (created 0700)
 * @param eventsRotation rotation settings for the supervisor events log
 * @param sampleInterval default resource sampling interval
 * @param services       services in declaration order
 * @param warnings       non-fatal validation findings
 */
public record ProcPilotConfig(
        Path source,
        Path logDir,
        Path runtimeDir,
        RotationConfig eventsRotation,
        Duration sampleInterval,
        Map<String, ServiceConfig> services,
        List<String> warnings) {

    public ProcPilotConfig {
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        warnings = List.copyOf(warnings);
    }

    public Path socketPath() {
        return runtimeDir.resolve("procpilot.sock");
    }

    public Path eventsLog() {
        return logDir.resolve("procpilot-events.log");
    }

    public ServiceConfig service(String name) {
        return services.get(name);
    }
}
