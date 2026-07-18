package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class HologramFresnelJobExecutorTest {

    @Autowired FresnelJobExecutor executor;

    @Test
    void checkerJobReproducesSourceMaskAndReconstruction() throws Exception {
        Path jobPath = repositoryFile("docs/jobs/hologram/checker.fresnel");
        Map<String, byte[]> generated = new LinkedHashMap<>();

        FresnelJobExecutionResult result = executor.execute(
                Files.readAllBytes(jobPath),
                (artifact, content) -> generated.put(artifact.filename(), content.clone()));

        assertEquals("hologram", result.job().plugin().id());
        assertEquals(3, result.artifacts().size());
        Map<String, GeneratedArtifact> byId = result.artifacts().stream()
                .collect(Collectors.toMap(GeneratedArtifact::outputId, Function.identity()));

        assertEquals("target.png", byId.get("target-source").filename());
        assertNull(byId.get("target-source").dpi());
        assertEquals("hologram-mask.png",
                byId.get("documentation-preview").filename());
        assertEquals(1200.0, byId.get("documentation-preview").dpi());
        assertEquals("reconstruction.png",
                byId.get("reconstruction-preview").filename());
        assertNull(byId.get("reconstruction-preview").dpi());

        for (GeneratedArtifact artifact : result.artifacts()) {
            assertEquals("image/png", artifact.mediaType());
            assertEquals(512, artifact.widthPx());
            assertEquals(512, artifact.heightPx());
            assertTrue(generated.get(artifact.filename()).length > 100);

            Path tracked = repositoryFile(
                    "docs/assets/plugins/hologram/" + artifact.filename());
            String trackedHash = FresnelJobExecutor.normalizedSha256(
                    artifact.mediaType(), Files.readAllBytes(tracked), artifact.dpi());
            assertEquals(trackedHash, artifact.normalizedSha256(),
                    () -> tracked + " is stale relative to " + jobPath);
        }
    }

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
