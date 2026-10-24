package com.procpilot.config;

/**
 * Size-based log rotation.
 *
 * @param maxBytes rotate once the file reaches this size
 * @param keep     number of rotated files retained (file.1 .. file.keep)
 */
public record RotationConfig(long maxBytes, int keep) {

    public static RotationConfig defaults() {
        return new RotationConfig(10L * 1024 * 1024, 5);
    }
}
