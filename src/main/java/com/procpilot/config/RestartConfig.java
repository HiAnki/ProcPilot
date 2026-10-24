package com.procpilot.config;

import java.time.Duration;

/**
 * Restart behaviour for a service.
 *
 * @param policy       when to restart after the process exits
 * @param maxAttempts  maximum consecutive restarts before the service is marked FAILED
 * @param initialDelay backoff before the first restart
 * @param maxDelay     upper bound on the backoff delay
 * @param multiplier   exponential growth factor applied per consecutive failure
 * @param resetAfter   a run lasting at least this long resets the consecutive-failure counter
 */
public record RestartConfig(
        RestartPolicyType policy,
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double multiplier,
        Duration resetAfter) {

    public static RestartConfig defaults() {
        return new RestartConfig(RestartPolicyType.NEVER, 5, Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0,
                Duration.ofSeconds(60));
    }
}
