package org.fresnel.backend.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimaryInstanceCoordinatorTest {

    @TempDir Path tempDir;

    @Test
    void electsOnePrimaryPublishesMetadataAndAllowsReelection() throws Exception {
        DesktopInstanceMetadata metadata = new DesktopInstanceMetadata(
                1, 18080, ProcessHandle.current().pid(),
                "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG", 1_700_000_000_000L);

        PrimaryInstanceCoordinator first = PrimaryInstanceCoordinator.tryAcquire(tempDir)
                .orElseThrow();
        try {
            assertTrue(PrimaryInstanceCoordinator.tryAcquire(tempDir).isEmpty());
            assertTrue(PrimaryInstanceCoordinator.readPublished(tempDir).isEmpty());

            first.publish(metadata);
            assertEquals(metadata, PrimaryInstanceCoordinator.readPublished(tempDir).orElseThrow());
        } finally {
            first.close();
        }

        assertTrue(PrimaryInstanceCoordinator.readPublished(tempDir).isEmpty());
        try (PrimaryInstanceCoordinator second = PrimaryInstanceCoordinator.tryAcquire(tempDir)
                .orElseThrow()) {
            assertTrue(Files.isRegularFile(tempDir.resolve(
                    PrimaryInstanceCoordinator.LOCK_FILENAME)));
        }
    }

    @Test
    void removesMetadataLeftByACrashedFormerOwnerOnlyAfterTakingTheLock() throws Exception {
        Path stale = tempDir.resolve(PrimaryInstanceCoordinator.METADATA_FILENAME);
        Files.writeString(stale, "not-valid-metadata=true\n");
        assertTrue(Files.exists(stale));

        try (PrimaryInstanceCoordinator coordinator =
                     PrimaryInstanceCoordinator.tryAcquire(tempDir).orElseThrow()) {
            assertFalse(Files.exists(stale));
            assertEquals(stale.toAbsolutePath().normalize(),
                    coordinator.metadataPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        PrimaryInstanceCoordinator coordinator =
                PrimaryInstanceCoordinator.tryAcquire(tempDir).orElseThrow();
        coordinator.close();
        coordinator.close();
        try (PrimaryInstanceCoordinator reacquired =
                     PrimaryInstanceCoordinator.tryAcquire(tempDir).orElseThrow()) {
            assertTrue(Files.isRegularFile(tempDir.resolve(
                    PrimaryInstanceCoordinator.LOCK_FILENAME)));
        }
    }
}
