package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class HologramFresnelJobExecutorTest {

    @Autowired FresnelJobExecutor executor;

    @Test
    void rejectsUnknownHologramPngKindsAndOptionFields() throws Exception {
        String job = Files.readString(repositoryFile(
                "docs/jobs/hologram/checker.fresnel"));

        IllegalArgumentException valueError = assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(
                        job.replaceFirst("\"SOURCE\"", "\"REMOTE\"").getBytes(),
                        (artifact, content) -> {}));
        assertTrue(valueError.getMessage().contains("SOURCE, MASK or RECONSTRUCTION"));

        IllegalArgumentException fieldError = assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(
                        job.replaceFirst(
                                "\"hologramPng\": \"SOURCE\"",
                                "\"moduleUrl\": \"https://example.invalid/widget.js\"")
                                .getBytes(),
                        (artifact, content) -> {}));
        assertTrue(fieldError.getMessage().contains("Unsupported Hologram PNG option"));
    }

    private static Path repositoryFile(String relativePath) {
        for (Path candidate : List.of(
                Path.of(relativePath),
                Path.of("..").resolve(relativePath))) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Could not locate repository file: " + relativePath);
    }
}
