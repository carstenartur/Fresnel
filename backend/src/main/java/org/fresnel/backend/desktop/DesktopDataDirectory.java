package org.fresnel.backend.desktop;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Resolves the per-user directory shared by the standalone database and desktop lock files. */
public final class DesktopDataDirectory {

    private DesktopDataDirectory() {}

    public static Path resolve() {
        return resolve(System.getenv(), System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", ".")));
    }

    static Path resolve(Map<String, String> environment, String osName, Path userHome) {
        String override = environment.get("FRESNEL_DATA_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = environment.get("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "Fresnel").toAbsolutePath().normalize();
            }
        }
        if (os.contains("mac")) {
            return userHome.resolve("Library").resolve("Application Support").resolve("Fresnel")
                    .toAbsolutePath().normalize();
        }

        String xdgDataHome = environment.get("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "Fresnel").toAbsolutePath().normalize();
        }
        return userHome.resolve(".local").resolve("share").resolve("Fresnel")
                .toAbsolutePath().normalize();
    }
}
