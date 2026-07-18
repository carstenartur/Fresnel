package org.fresnel.backend.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class FresnelDocumentationRendererTest {

    private static final List<Example> MIGRATED_EXAMPLES = List.of(
            new Example("zone-plate", "on-axis", "zone-plate.md"),
            new Example("zone-plate", "greyscale-phase", "zone-plate.md"),
            new Example("zone-plate", "negative-polarity", "zone-plate.md"),
            new Example("hex-macro-cell", "on-axis", "hex-macro-cell.md"),
            new Example("multi-focus", "two-foci", "multi-focus.md"),
            new Example("multi-focus", "line-focus", "multi-focus.md"),
            new Example("rgb-zone-plate", "rgb", "rgb-zone-plate.md"));

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
