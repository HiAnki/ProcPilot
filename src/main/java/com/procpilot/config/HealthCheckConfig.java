package com.procpilot.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Optional health check. Exactly one of {@code httpUrl} or {@code command} is set.
 *
 * @param httpUrl          URL that must answer with a 2xx/3xx status
 * @param command          argv of a command that must exit 0
 * @param interval         time between checks
 * @param timeout          per-check timeout (connect + response, or command runtime)
 * @param failureThreshold consecutive failures before the service is considered unhealthy and is restarted
 * @param startPeriod      grace period after start before checks begin
 */
public record HealthCheckConfig(
        URI httpUrl,
        List<String> command,
        Duration interval,
        Duration timeout,
        int failureThreshold,
        Duration startPeriod) {

    public HealthCheckConfig {
        command = command == null ? null : List.copyOf(command);
    }

    public boolean isHttp() {
        return httpUrl != null;
    }

    public String describe() {
        return isHttp() ? "http " + httpUrl : "command " + command.get(0);
    }
}
