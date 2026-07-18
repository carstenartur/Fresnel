package org.fresnel.backend.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;

/** Small pre-Spring diagnostic log for native launcher and browser-open failures. */
public final class DesktopDiagnostics {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private DesktopDiagnostics() {}

    public static Path launcherLog(Path dataDirectory) {
        return dataDirectory.toAbsolutePath().normalize()
                .resolve("logs").resolve("desktop-launcher.log");
    }

    public static Path applicationLog(Path dataDirectory) {
        return dataDirectory.toAbsolutePath().normalize()
                .resolve("logs").resolve("fresnel.log");
    }

    /**
     * Appends one bounded line. Logging itself must never hide the original desktop
     * failure, therefore I/O problems are reported only to stderr.
     */
    public static void append(Path dataDirectory, String message, Throwable failure) {
        try {
            Path log = launcherLog(dataDirectory);
            Files.createDirectories(log.getParent());
            secureDirectory(log.getParent());

            String detail = failure == null ? "" : " | " + failure.getClass().getSimpleName()
                    + ": " + safe(failure.getMessage());
            String line = Instant.now() + " | " + safe(message) + detail + System.lineSeparator();
            Files.writeString(log, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            secureFile(log);
        } catch (IOException | RuntimeException loggingFailure) {
            System.err.println("Could not write Fresnel desktop diagnostic log: "
                    + safe(loggingFailure.getMessage()));
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "no details";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (singleLine.length() > 1000) return singleLine.substring(0, 1000);
        return singleLine;
    }

    private static void secureDirectory(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        }
    }

    private static void secureFile(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        }
    }
}
