package org.fresnel.backend.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopDiagnosticsTest {

    @TempDir Path tempDir;

    @Test
    void appendsSingleLineLauncherDiagnosticsAndCreatesTheLogDirectory() throws Exception {
        DesktopDiagnostics.append(
                tempDir,
                "Launcher failed\nwith a second line",
                new IllegalStateException("port conflict\r\nmore detail"));
        DesktopDiagnostics.append(tempDir, "Second event", null);

        Path log = DesktopDiagnostics.launcherLog(tempDir);
        assertTrue(Files.isRegularFile(log));
        String content = Files.readString(log);
        assertTrue(content.contains("Launcher failed with a second line"));
        assertTrue(content.contains("IllegalStateException: port conflict  more detail"));
        assertTrue(content.contains("Second event"));
        assertEquals(2, content.lines().count());
        assertFalse(content.contains("\r"));
    }

    @Test
    void logLocationsRemainInsideTheResolvedDataDirectory() {
        Path launcher = DesktopDiagnostics.launcherLog(tempDir);
        Path application = DesktopDiagnostics.applicationLog(tempDir);
        assertTrue(launcher.startsWith(tempDir.toAbsolutePath().normalize()));
        assertTrue(application.startsWith(tempDir.toAbsolutePath().normalize()));
        assertEquals(Path.of("logs", "desktop-launcher.log"),
                tempDir.toAbsolutePath().normalize().relativize(launcher));
        assertEquals(Path.of("logs", "fresnel.log"),
                tempDir.toAbsolutePath().normalize().relativize(application));
    }

    @Test
    void posixLogPermissionsAreOwnerOnlyWhenSupported() throws Exception {
        DesktopDiagnostics.append(tempDir, "permission check", null);
        Path log = DesktopDiagnostics.launcherLog(tempDir);
        if (Files.getFileStore(log).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(log));
        }
    }
}
