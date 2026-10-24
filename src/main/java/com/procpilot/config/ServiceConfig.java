package com.procpilot.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A validated, immutable service definition.
 *
 * @param command        argv; element 0 is the resolved absolute executable path
 * @param env            extra environment variables merged over the supervisor's environment
 * @param secretEnv      names of env vars whose values must never be displayed or logged
 * @param healthCheck    may be {@code null}
 * @param sampleInterval resource sampling interval for {@code monitor}; may be {@code null} (use global default)
 */
public record ServiceConfig(
        String name,
        List<String> command,
        Path workingDir,
        Map<String, String> env,
        Set<String> secretEnv,
        Path stdoutLog,
        Path stderrLog,
        RestartConfig restart,
        Duration stopTimeout,
        HealthCheckConfig healthCheck,
        Duration sampleInterval,
        RotationConfig logRotation) {

    public ServiceConfig {
        command = List.copyOf(command);
        env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        secretEnv = Set.copyOf(secretEnv);
    }

    public String executable() {
        return command.get(0);
    }
}
