package org.fresnel.backend.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class FresnelDocumentationManifestPortabilityTest {

    @Autowired FresnelDocumentationManifestService service;

    @TempDir Path tempDir;

    @Test
    void rejectsCrossJobArtifactCollisionsOnCaseInsensitiveFilesystems() throws Exception {
        Path jobRoot = Files.createDirectories(tempDir.resolve("jobs/zone-plate"));
        Path assetRoot = Files.createDirectories(tempDir.resolve("assets/zone-plate"));

        String source = Files.readString(repositoryFile(
                "docs/jobs/zone-plate/on-axis.fresnel"));
        Files.writeString(jobRoot.resolve("first.fresnel"), source);
        Files.writeString(jobRoot.resolve("second.fresnel"), source.replace(
                "\"filename\": \"on-axis.png\"",
                "\"filename\": \"ON-AXIS.PNG\""));

        Path tracked = repositoryFile("docs/assets/plugins/zone-plate/on-axis.png");
        Files.copy(tracked, assetRoot.resolve("on-axis.png"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(tracked, assetRoot.resolve("ON-AXIS.PNG"),
                StandardCopyOption.REPLACE_EXISTING);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.generate(tempDir.resolve("jobs"), tempDir.resolve("assets")));
        assertTrue(error.getMessage().contains("case-insensitive filesystem"));
    }

    @Test
    void usesTheRepositoryDocsDirectoryWhenAnAncestorIsAlsoNamedDocs() throws Exception {
        Path repositoryRoot = tempDir.resolve("docs/checkout");
        Path jobRoot = Files.createDirectories(
                repositoryRoot.resolve("docs/jobs/zone-plate"));
        Path assetRoot = Files.createDirectories(
                repositoryRoot.resolve("docs/assets/plugins/zone-plate"));

        Files.copy(
                repositoryFile("docs/jobs/zone-plate/on-axis.fresnel"),
                jobRoot.resolve("on-axis.fresnel"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                repositoryFile("docs/assets/plugins/zone-plate/on-axis.png"),
                assetRoot.resolve("on-axis.png"),
                StandardCopyOption.REPLACE_EXISTING);

        FresnelDocumentationManifest manifest = service.generate(
                repositoryRoot.resolve("docs/jobs"),
                repositoryRoot.resolve("docs/assets/plugins"));

        assertEquals(1, manifest.examples().size());
        FresnelDocumentationManifest.Example example = manifest.examples().getFirst();
        assertEquals("docs/jobs/zone-plate/on-axis.fresnel", example.job());
        assertEquals(
                "docs/assets/plugins/zone-plate/on-axis.png",
                example.artifacts().getFirst().path());
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
