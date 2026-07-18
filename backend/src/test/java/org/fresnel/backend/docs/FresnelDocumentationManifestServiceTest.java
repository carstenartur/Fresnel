package org.fresnel.backend.docs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FresnelDocumentationManifestServiceTest {

    private static final List<String> MIGRATED_EXAMPLES = List.of(
            "hex-macro-cell/on-axis",
            "hologram/checker",
            "multi-focus/line-focus",
            "multi-focus/two-foci",
            "rgb-zone-plate/rgb",
            "window-foil/foil-sheet",
            "zone-plate/greyscale-phase",
            "zone-plate/negative-polarity",
            "zone-plate/on-axis");
    private static final Set<String> MIGRATED_PLUGINS = Set.of(
            "hex-macro-cell",
            "hologram",
            "multi-focus",
            "rgb-zone-plate",
            "window-foil",
            "zone-plate");
    private static final Map<String, Double> EXPECTED_SINGLE_ARTIFACT_DPI = Map.of(
            "hex-macro-cell", 400.0,
            "multi-focus", 1200.0,
            "rgb-zone-plate", 1200.0,
            "window-foil", 200.0,
            "zone-plate", 1200.0);

    @Autowired FresnelDocumentationManifestService service;

    private Path assets;
    private FresnelDocumentationManifest manifest;

    @BeforeAll
    void generateManifestOnceForTheReadOnlyContractTests() throws Exception {
        Path jobs = repositoryDirectory("docs/jobs");
        assets = repositoryDirectory("docs/assets/plugins");
        manifest = service.generate(jobs, assets);
    }

    @Test
    void manifestTracesEveryMigratedJobToVerifiedArtifacts() {
        assertEquals(1, manifest.formatVersion());
        assertEquals(MIGRATED_EXAMPLES,
                manifest.examples().stream()
                        .map(FresnelDocumentationManifest.Example::id)
                        .toList());

        for (FresnelDocumentationManifest.Example example : manifest.examples()) {
            assertTrue(MIGRATED_PLUGINS.contains(example.pluginId()));
            assertEquals(1, example.jobFormatVersion());
            assertEquals(1, example.parameterSchemaVersion());
            assertEquals(example.pluginId() + "/1", example.algorithmVersion());
            assertTrue(example.parameterSha256().matches("[0-9a-f]{64}"));

            if ("hologram".equals(example.pluginId())) {
                assertHologramArtifacts(example.artifacts());
            } else {
                assertEquals(1, example.artifacts().size());
                FresnelDocumentationManifest.Artifact artifact =
                        example.artifacts().getFirst();
                assertEquals("documentation-preview", artifact.id());
                assertEquals("image/png", artifact.mediaType());
                assertTrue(artifact.sizeBytes() > 100);
                assertTrue(artifact.widthPx() >= 400);
                assertTrue(artifact.heightPx() >= 300);
                assertEquals(EXPECTED_SINGLE_ARTIFACT_DPI.get(example.pluginId()),
                        artifact.dpi());
                assertTrackedArtifact(artifact);
            }
        }
    }

    private static void assertHologramArtifacts(
            List<FresnelDocumentationManifest.Artifact> artifacts) {
        assertEquals(3, artifacts.size());
        Map<String, FresnelDocumentationManifest.Artifact> byId = artifacts.stream()
                .collect(Collectors.toMap(
                        FresnelDocumentationManifest.Artifact::id,
                        Function.identity()));

        FresnelDocumentationManifest.Artifact source = byId.get("target-source");
        FresnelDocumentationManifest.Artifact mask = byId.get("documentation-preview");
        FresnelDocumentationManifest.Artifact reconstruction =
                byId.get("reconstruction-preview");

        assertEquals("target.png", source.path().substring(source.path().lastIndexOf('/') + 1));
        assertEquals("hologram-mask.png",
                mask.path().substring(mask.path().lastIndexOf('/') + 1));
        assertEquals("reconstruction.png",
                reconstruction.path().substring(reconstruction.path().lastIndexOf('/') + 1));
        assertNull(source.dpi());
        assertEquals(1200.0, mask.dpi());
        assertNull(reconstruction.dpi());

        for (FresnelDocumentationManifest.Artifact artifact : artifacts) {
            assertEquals("image/png", artifact.mediaType());
            assertTrue(artifact.sizeBytes() > 100);
            assertEquals(512, artifact.widthPx());
            assertEquals(512, artifact.heightPx());
            assertTrackedArtifact(artifact);
        }
    }

    private static void assertTrackedArtifact(
            FresnelDocumentationManifest.Artifact artifact) {
        assertTrue(artifact.normalizedSha256().matches("[0-9a-f]{64}"));
        assertTrue(Files.isRegularFile(repositoryFile(artifact.path())));
    }

    @Test
    void manifestSerializationIsDeterministicAndMigratedPngsHaveNoOrphans()
            throws Exception {
        assertEquals(
                new String(service.write(manifest), StandardCharsets.UTF_8),
                new String(service.write(manifest), StandardCharsets.UTF_8));

        Set<String> manifestAssets = manifest.examples().stream()
                .flatMap(example -> example.artifacts().stream())
                .map(FresnelDocumentationManifest.Artifact::path)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> trackedPngs = new LinkedHashSet<>();
        for (String pluginId : MIGRATED_PLUGINS) {
            try (var paths = Files.list(assets.resolve(pluginId))) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".png"))
                        .map(path -> "docs/assets/plugins/" + pluginId + "/" + path.getFileName())
                        .sorted()
                        .forEach(trackedPngs::add);
            }
        }
        assertEquals(trackedPngs, manifestAssets);
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
