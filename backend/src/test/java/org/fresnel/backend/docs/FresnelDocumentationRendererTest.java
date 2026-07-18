package org.fresnel.backend.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class FresnelDocumentationRendererTest {

    private static final List<Example> MIGRATED_EXAMPLES = List.of(
            new Example("zone-plate", "on-axis", "zone-plate.md"),
            new Example("zone-plate", "greyscale-phase", "zone-plate.md"),
            new Example("zone-plate", "negative-polarity", "zone-plate.md"),
            new Example("hex-macro-cell", "on-axis", "hex-macro-cell.md"),
            new Example("window-foil", "foil-sheet", "window-foil.md"),
            new Example("multi-focus", "two-foci", "multi-focus.md"),
            new Example("multi-focus", "line-focus", "multi-focus.md"),
            new Example("rgb-zone-plate", "rgb", "rgb-zone-plate.md"),
            new Example("hologram", "checker", "hologram.md"));

    @Autowired FresnelDocumentationRenderer renderer;

    @Test
    void parameterBlocksAreGeneratedFromJobsAndSchemaMetadata() throws Exception {
        for (Example example : MIGRATED_EXAMPLES) {
            String exampleId = example.pluginId() + "/" + example.jobName();
            String markdown = Files.readString(repositoryFile(
                    "docs/plugins/" + example.documentationPage()));
            byte[] job = Files.readAllBytes(repositoryFile(
                    "docs/jobs/" + example.pluginId() + "/"
                            + example.jobName() + ".fresnel"));
            String generated = renderer.renderMarkedParameterBlock(exampleId, job);
            assertTrue(markdown.contains(generated),
                    () -> "Stale generated parameter block for " + exampleId);
        }
    }

    @Test
    void markedBlocksRejectUnsafeHtmlCommentIdentifiers() throws Exception {
        byte[] job = Files.readAllBytes(repositoryFile(
                "docs/jobs/zone-plate/on-axis.fresnel"));

        for (String unsafe : List.of(
                "zone-plate/bad--marker",
                "zone-plate/../escape",
                "zone plate/on-axis",
                "/zone-plate")) {
            assertThrows(IllegalArgumentException.class,
                    () -> renderer.renderMarkedParameterBlock(unsafe, job), unsafe);
        }
    }

    @Test
    void embeddedBase64DataIsSummarizedWithoutLeakingItIntoMarkdown() {
        String embedded =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNoAAAAggCBd81ytgAAAABJRU5ErkJggg==";
        byte[] job = ("""
                {
                  "format": "io.github.carstenartur.fresnel.job",
                  "formatVersion": 1,
                  "plugin": {
                    "id": "hologram",
                    "parameterSchemaVersion": 1,
                    "algorithmVersion": "hologram/1"
                  },
                  "parameters": {
                    "targetImageBase64": "%s",
                    "sidePx": 16,
                    "iterations": 1,
                    "outputType": "GREYSCALE_PHASE",
                    "dpi": 600,
                    "wavelengthNm": 550,
                    "refractiveIndexDelta": 0.5,
                    "maxPhaseShiftRad": 6.283185307179586
                  }
                }
                """).formatted(embedded).getBytes(StandardCharsets.UTF_8);

        String table = renderer.renderParameterTable(job);

        assertTrue(table.contains("Embedded data (approximately 67 bytes decoded)"));
        assertFalse(table.contains(embedded));
        assertFalse(table.contains("iVBORw0KGgo"));
    }

    private static Path repositoryFile(String relativePath) {
        for (Path candidate : List.of(
                Path.of(relativePath),
                Path.of("..").resolve(relativePath))) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Could not locate repository file: " + relativePath);
    }

    private record Example(String pluginId, String jobName, String documentationPage) {}
}
