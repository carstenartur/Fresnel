package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.CaptureProvider.FailureCode;
import org.fresnel.measurement.CaptureProviderException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Immutable pairing-scoped requests, session plans and original asset identities. */
final class PhotographerJournal {
    private final Path directory;

    PhotographerJournal(Path directory) {
        this.directory = directory;
    }

    synchronized byte[] pin(PhotographerPairing.Connection connection, List<String> keys, byte[] bytes,
                            FailureCode conflict, boolean expiringCreate) {
        Path file = path(connection, keys);
        try {
            Files.createDirectories(directory);
            if (!Files.exists(file)) {
                Path pending = Files.createTempFile(directory, "pending-", ".tmp");
                try {
                    Files.write(pending, bytes);
                    try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    // Publish only complete immutable data, including concurrent first writers.
                    try {
                        Files.createLink(file, pending);
                    } catch (java.nio.file.FileAlreadyExistsException raced) {
                        // Compare the winning record below.
                    }
                } finally {
                    Files.deleteIfExists(pending);
                }
            }
            byte[] original = Files.readAllBytes(file);
            if (!Arrays.equals(original, bytes)) {
                throw new CaptureProviderException(conflict, false, conflict == FailureCode.INTEGRITY_MISMATCH
                        ? "PHOTOGRAPHER_ASSET_MISMATCH" : "IDEMPOTENCY_CONFLICT");
            }
            if (expiringCreate && Files.getLastModifiedTime(file).compareTo(
                    FileTime.from(Instant.now().minus(Duration.ofDays(1)))) < 0) {
                throw new CaptureProviderException(FailureCode.CONFLICT, false, "CREATE_REPLAY_WINDOW_ELAPSED");
            }
            return original;
        } catch (IOException ex) {
            throw storage();
        }
    }

    byte[] read(PhotographerPairing.Connection connection, List<String> keys) {
        try {
            return Files.readAllBytes(path(connection, keys));
        } catch (java.nio.file.NoSuchFileException ex) {
            throw new CaptureProviderException(FailureCode.SESSION_NOT_FOUND, false, "LOCAL_SESSION_NOT_FOUND");
        } catch (IOException ex) {
            throw storage();
        }
    }

    private Path path(PhotographerPairing.Connection connection, List<String> keys) {
        var digest = sha256();
        digest.update((connection.origin() + "\n" + connection.instanceId() + "\n" + connection.pairingId() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        for (String key : keys) {
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return directory.resolve(HexFormat.of().formatHex(digest.digest()));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static CaptureProviderException storage() {
        return new CaptureProviderException(FailureCode.INTERNAL_ERROR, false, "LOCAL_CAPTURE_JOURNAL_UNAVAILABLE");
    }
}
