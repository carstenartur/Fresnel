package org.fresnel.backend.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopDataDirectoryTest {

    @TempDir Path tempDir;

    @Test
    void systemPropertyOverrideWinsOverTheEnvironment() {
        Path systemOverride = tempDir.resolve("system data");
        Path environmentOverride = tempDir.resolve("environment data");
        assertEquals(systemOverride.toAbsolutePath().normalize(), DesktopDataDirectory.resolve(
                systemOverride.toString(),
                Map.of("FRESNEL_DATA_DIR", environmentOverride.toString()),
                "Windows 11",
                tempDir));
    }

    @Test
    void explicitEnvironmentOverrideWinsOnEveryPlatform() {
        Path override = tempDir.resolve("custom data");
        assertEquals(override.toAbsolutePath().normalize(), DesktopDataDirectory.resolve(
                Map.of("FRESNEL_DATA_DIR", override.toString()), "Windows 11", tempDir));
    }

    @Test
    void resolvesWindowsAppData() {
        Path appData = tempDir.resolve("Roaming");
        assertEquals(appData.resolve("Fresnel").toAbsolutePath().normalize(),
                DesktopDataDirectory.resolve(Map.of("APPDATA", appData.toString()),
                        "Windows 11", tempDir));
    }

    @Test
    void resolvesLinuxXdgAndFallback() {
        Path xdg = tempDir.resolve("xdg");
        assertEquals(xdg.resolve("Fresnel").toAbsolutePath().normalize(),
                DesktopDataDirectory.resolve(Map.of("XDG_DATA_HOME", xdg.toString()),
                        "Linux", tempDir));
        assertEquals(tempDir.resolve(".local/share/Fresnel").toAbsolutePath().normalize(),
                DesktopDataDirectory.resolve(Map.of(), "Linux", tempDir));
    }

    @Test
    void resolvesMacApplicationSupport() {
        assertEquals(tempDir.resolve("Library/Application Support/Fresnel")
                        .toAbsolutePath().normalize(),
                DesktopDataDirectory.resolve(Map.of(), "Mac OS X", tempDir));
    }
}
