package org.fresnel.backend.desktop;

import java.util.Properties;

/**
 * Minimal information needed by a secondary launcher invocation to authenticate
 * a loopback hand-off to the already-running primary instance.
 */
public record DesktopInstanceMetadata(
        int protocolVersion,
        int port,
        long processId,
        String sessionSecret,
        long startedAtEpochMillis
) {
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public DesktopInstanceMetadata {
        if (protocolVersion != CURRENT_PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported desktop protocol version: " + protocolVersion);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid desktop control port: " + port);
        }
        if (processId < 1) {
            throw new IllegalArgumentException("Invalid desktop process id: " + processId);
        }
        if (sessionSecret == null || sessionSecret.length() < 32) {
            throw new IllegalArgumentException("Desktop session secret is missing or too short");
        }
        if (startedAtEpochMillis < 1) {
            throw new IllegalArgumentException("Invalid desktop start timestamp");
        }
    }

    Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("protocolVersion", Integer.toString(protocolVersion));
        properties.setProperty("port", Integer.toString(port));
        properties.setProperty("processId", Long.toString(processId));
        properties.setProperty("sessionSecret", sessionSecret);
        properties.setProperty("startedAtEpochMillis", Long.toString(startedAtEpochMillis));
        return properties;
    }

    static DesktopInstanceMetadata fromProperties(Properties properties) {
        try {
            return new DesktopInstanceMetadata(
                    Integer.parseInt(required(properties, "protocolVersion")),
                    Integer.parseInt(required(properties, "port")),
                    Long.parseLong(required(properties, "processId")),
                    required(properties, "sessionSecret"),
                    Long.parseLong(required(properties, "startedAtEpochMillis")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Desktop instance metadata contains an invalid number", e);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Desktop instance metadata is missing " + key);
        }
        return value.trim();
    }
}
