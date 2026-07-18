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

    @Autowired FresnelDocumentationRenderer renderer;

    @Test
    void zonePlateParameterBlocksAreGeneratedFromJobsAndSchemaMetadata() throws Exception {
        String markdown = Files.readString(repositoryFile("docs/plugins/zone-plate.md"));
        for (String example : List.of(
                "on-axis",
                "greyscale-phase",
                "negative-polarity")) {
            byte[] job = Files.readAllBytes(repositoryFile(
                    "docs/jobs/zone-plate/" + example + ".fresnel"));
            String generated = renderer.renderMarkedParameterBlock(
                    "zone-plate/" + example, job);
            assertTrue(markdown.contains(generated),
                    () -> "Stale generated parameter block for zone-plate/" + example);
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
}
