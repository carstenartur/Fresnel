package org.fresnel.backend.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Elects one packaged desktop process as primary and publishes authenticated
 * loopback connection metadata for secondary invocations.
 */
public final class PrimaryInstanceCoordinator implements AutoCloseable {

    static final String LOCK_FILENAME = "desktop-instance.lock";
    static final String METADATA_FILENAME = "desktop-instance.properties";

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path metadataPath;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PrimaryInstanceCoordinator(Path metadataPath, FileChannel lockChannel, FileLock lock) {
        this.metadataPath = metadataPath;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    /**
     * Attempts to become the primary process. An empty result means another local
     * process currently owns the exclusive lock.
     */
    public static Optional<PrimaryInstanceCoordinator> tryAcquire(Path dataDirectory) throws IOException {
        Path directory = dataDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        secureDirectory(directory);

        Path lockPath = directory.resolve(LOCK_FILENAME);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        secureFile(lockPath);

        final FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            channel.close();
            return Optional.empty();
        }
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }

        Path metadataPath = directory.resolve(METADATA_FILENAME);
        // A lock owner is authoritative. Metadata left by a crashed former process
        // is stale and may be removed only after this process acquired the lock.
        Files.deleteIfExists(metadataPath);
        return Optional.of(new PrimaryInstanceCoordinator(metadataPath, channel, lock));
    }

    public Path metadataPath() {
        return metadataPath;
    }

    /** Atomically publishes metadata only after the embedded web server is ready. */
    public void publish(DesktopInstanceMetadata metadata) throws IOException {
        ensureOpen();
        Path temporary = Files.createTempFile(metadataPath.getParent(), ".desktop-instance-", ".tmp");
        try {
            secureFile(temporary);
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                metadata.toProperties().store(output, "Fresnel desktop instance v1");
            }
            try {
                Files.move(temporary, metadataPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, metadataPath, StandardCopyOption.REPLACE_EXISTING);
            }
            secureFile(metadataPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static Optional<DesktopInstanceMetadata> readPublished(Path dataDirectory) throws IOException {
        Path metadataPath = dataDirectory.toAbsolutePath().normalize().resolve(METADATA_FILENAME);
        if (!Files.isRegularFile(metadataPath) || !Files.isReadable(metadataPath)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadataPath)) {
            properties.load(input);
        }
        return Optional.of(DesktopInstanceMetadata.fromProperties(properties));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.deleteIfExists(metadataPath);
        } catch (IOException ignored) {
            // The lock release remains the authoritative shutdown signal.
        }
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Closing the channel below releases the lock as a final fallback.
        }
        try {
            lockChannel.close();
        } catch (IOException ignored) {
            // Nothing useful can be done during JVM shutdown.
        }
    }

    private void ensureOpen() {
        if (closed.get() || !lock.isValid()) {
            throw new IllegalStateException("Desktop instance coordinator is closed");
        }
    }

    private static void secureDirectory(Path path) throws IOException {
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        }
    }

    private static void secureFile(Path path) throws IOException {
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        }
    }

    private static boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }
}
