package org.fresnel.backend.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class FresnelDocumentationManifestServiceTest {

    @Autowired FresnelDocumentationManifestService service;

    @Test
    void manifestTracesEveryZonePlateJobToOneVerifiedArtifact() throws Exception {
        Path jobs = repositoryDirectory("docs/jobs");
        Path assets = repositoryDirectory("docs/assets/plugins");

        FresnelDocumentationManifest manifest = service.generate(jobs, assets);
        assertEquals(1, manifest.formatVersion());
        assertEquals(List.of(
                        "zone-plate/greyscale-phase",
                        "zone-plate/negative-polarity",
                        "zone-plate/on-axis"),
                manifest.examples().stream()
                        .map(FresnelDocumentationManifest.Example::id)
                        .toList());

        for (FresnelDocumentationManifest.Example example : manifest.examples()) {
            assertEquals("zone-plate", example.pluginId());
            assertEquals(1, example.jobFormatVersion());
            assertEquals(1, example.parameterSchemaVersion());
            assertEquals("zone-plate/1", example.algorithmVersion());
            assertTrue(example.parameterSha256().matches("[0-9a-f]{64}"));
            assertEquals(1, example.artifacts().size());

            FresnelDocumentationManifest.Artifact artifact = example.artifacts().getFirst();
            assertEquals("documentation-preview", artifact.id());
            assertEquals("image/png", artifact.mediaType());
            assertTrue(artifact.sizeBytes() > 100);
            assertTrue(artifact.widthPx() >= 400);
            assertTrue(artifact.heightPx() >= 400);
            assertEquals(1200.0, artifact.dpi());
            assertTrue(artifact.normalizedSha256().matches("[0-9a-f]{64}"));
            assertTrue(Files.isRegularFile(repositoryFile(artifact.path())));
        }
    }

    @Test
    void manifestSerializationIsDeterministicAndHasNoOrphanedZonePlatePngs()
            throws Exception {
        Path jobs = repositoryDirectory("docs/jobs");
        Path assets = repositoryDirectory("docs/assets/plugins");
        FresnelDocumentationManifest first = service.generate(jobs, assets);
        FresnelDocumentationManifest second = service.generate(jobs, assets);
        assertEquals(new String(service.write(first)), new String(service.write(second)));

        Set<String> manifestAssets = first.examples().stream()
                .flatMap(example -> example.artifacts().stream())
                .map(FresnelDocumentationManifest.Artifact::path)
                .collect(Collectors.toSet());
        try (var paths = Files.list(assets.resolve("zone-plate"))) {
            Set<String> trackedPngs = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .map(path -> "docs/assets/plugins/zone-plate/" + path.getFileName())
                    .collect(Collectors.toSet());
            assertEquals(trackedPngs, manifestAssets);
        }
    }

    private static Path repositoryDirectory(String relativePath) {
        for (Path candidate : List.of(
                Path.of(relativePath),
                Path.of("..").resolve(relativePath))) {
            if (Files.isDirectory(candidate)) return candidate.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Could not locate repository directory: " + relativePath);
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
