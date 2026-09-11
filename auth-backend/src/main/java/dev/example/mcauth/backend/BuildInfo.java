package dev.example.mcauth.backend;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record BuildInfo(String application, String version, String commit, String buildTime) {
    private static final String RESOURCE = "/build-info.properties";

    public static BuildInfo load() {
        Properties properties = new Properties();
        try (InputStream stream = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) return unknown();
            properties.load(stream);
            return new BuildInfo(
                    safe(properties.getProperty("application.name"), "minecraft-auth-backend"),
                    safe(properties.getProperty("application.version"), "unknown"),
                    safe(properties.getProperty("git.commit"), "unknown"),
                    safe(properties.getProperty("build.time"), "unknown"));
        } catch (IOException exception) {
            return unknown();
        }
    }

    private static BuildInfo unknown() {
        return new BuildInfo("minecraft-auth-backend", "unknown", "unknown", "unknown");
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank() || value.length() > 128) return fallback;
        return value.matches("[A-Za-z0-9._:+-]+") ? value : fallback;
    }
}
