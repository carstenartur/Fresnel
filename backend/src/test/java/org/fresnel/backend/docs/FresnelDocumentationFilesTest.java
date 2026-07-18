package org.fresnel.backend.docs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FresnelDocumentationFilesTest {

    @TempDir Path tempDir;

    @Test
    void discoversOrdinaryJobsInPortableRelativeOrder() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("jobs"));
        Files.writeString(root.resolve("z-last.fresnel"), "{}");
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/a-first.fresnel"), "{}");
        Files.writeString(root.resolve("ignore.json"), "{}");

        List<String> relative = FresnelDocumentationFiles.discoverJobs(root).stream()
                .map(root.toRealPath()::relativize)
                .map(FresnelDocumentationFiles::portable)
                .toList();

        assertEquals(List.of("nested/a-first.fresnel", "z-last.fresnel"), relative);
    }

    @Test
    void discoveryDoesNotFollowASymbolicJobFile() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("jobs"));
        Path outside = tempDir.resolve("outside.fresnel");
        Files.writeString(outside, "{}");
        createSymbolicLinkOrAbort(root.resolve("linked.fresnel"), outside);

        assertEquals(List.of(), FresnelDocumentationFiles.discoverJobs(root));
    }

    @Test
    void trackedArtifactsCannotUseSymbolicLinks() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("assets"));
        Path outside = tempDir.resolve("outside.png");
        Files.write(outside, new byte[]{1, 2, 3});
        createSymbolicLinkOrAbort(root.resolve("linked.png"), outside);

        assertThrows(IllegalStateException.class,
                () -> FresnelDocumentationFiles.requireRegularDescendant(
                        root.toRealPath(), Path.of("linked.png"), "documentation artifact"));
    }

    @Test
    void atomicWriteReplacesASymbolicLinkWithoutFollowingIt() throws Exception {
        Path outputDirectory = Files.createDirectories(tempDir.resolve("output"));
        Path outside = tempDir.resolve("outside.json");
        Path output = outputDirectory.resolve("manifest.json");
        byte[] originalOutside = {8, 7, 6};
        byte[] replacement = {1, 2, 3, 4};
        Files.write(outside, originalOutside);
        createSymbolicLinkOrAbort(output, outside);

        FresnelDocumentationFiles.writeAtomically(output, replacement);

        assertFalse(Files.isSymbolicLink(output));
        assertArrayEquals(replacement, Files.readAllBytes(output));
        assertArrayEquals(originalOutside, Files.readAllBytes(outside));
    }

    @Test
    void portableKeysNormalizeCaseSeparatorsAndUnicodeComposition() {
        assertEquals(
                FresnelDocumentationFiles.portableKey("Plugin\\Caf\u00e9.PNG"),
                FresnelDocumentationFiles.portableKey("plugin/Cafe\u0301.png"));
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false,
                    "symbolic links are unavailable in this test environment: " + e.getMessage());
        }
    }
}
