package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class FresnelJobExecutorTest {

    private static final List<String> ZONE_PLATE_EXAMPLES = List.of(
            "on-axis",
            "greyscale-phase",
            "negative-polarity");

    @Autowired FresnelJobExecutor executor;

    @TempDir Path tempDir;

    @Test
    void trackedZonePlateImagesAreGeneratedFromTheirPublicJobs() throws Exception {
        for (String example : ZONE_PLATE_EXAMPLES) {
            Path jobPath = repositoryFile(
                    "docs/jobs/zone-plate/" + example + ".fresnel");
            Map<String, byte[]> generated = new LinkedHashMap<>();

            FresnelJobExecutionResult result = executor.execute(
                    Files.readAllBytes(jobPath),
                    (artifact, content) -> generated.put(artifact.filename(), content.clone()));

            assertEquals("zone-plate", result.job().plugin().id());
            assertEquals(1, result.artifacts().size());
            GeneratedArtifact artifact = result.artifacts().getFirst();
            assertEquals(example + ".png", artifact.filename());
            assertEquals("image/png", artifact.mediaType());
            assertEquals(1200.0, artifact.dpi());
            assertTrue(artifact.widthPx() >= 400);
            assertTrue(artifact.heightPx() >= 400);
            assertFalse(generated.get(artifact.filename()).length == 0);

            Path tracked = repositoryFile(
                    "docs/assets/plugins/zone-plate/" + artifact.filename());
            String trackedHash = FresnelJobExecutor.normalizedSha256(
                    artifact.mediaType(), Files.readAllBytes(tracked), artifact.dpi());
            assertEquals(trackedHash, artifact.normalizedSha256(),
                    () -> tracked + " is stale relative to " + jobPath);
        }
    }

    @Test
    void zonePlateProductionPlanExecutesEveryAdvertisedExportFormat() throws Exception {
        FresnelJobExecutionResult source = executor.execute(
                Files.readAllBytes(repositoryFile(
                        "docs/jobs/zone-plate/on-axis.fresnel")),
                (artifact, content) -> {});
        FresnelJobDocument job = source.job();
        FresnelJobDocument.ProductionPlan production = new FresnelJobDocument.ProductionPlan(List.of(
                output("png", "preview.png", null, null),
                output("svg", "plot.svg", null, null),
                output("pdf", "print.pdf", "A4", 1.0),
                output("dxf", "outlines.dxf", null, null),
                output("gerber", "photoplot.gbr", null, null)));
        FresnelJobDocument multiOutput = new FresnelJobDocument(
                job.schema(), job.format(), job.formatVersion(), job.plugin(),
                job.parameters(), production, job.provenance());

        Map<String, byte[]> generated = new LinkedHashMap<>();
        FresnelJobExecutionResult result = executor.execute(
                multiOutput,
                (artifact, content) -> generated.put(artifact.filename(), content.clone()));

        assertEquals(Set.of(
                        "image/png",
                        "image/svg+xml",
                        "application/pdf",
                        "application/dxf",
                        "application/vnd.gerber"),
                result.artifacts().stream().map(GeneratedArtifact::mediaType).collect(
                        java.util.stream.Collectors.toSet()));
        assertEquals(5, generated.size());
        assertTrue(generated.values().stream().allMatch(content -> content.length > 64));
    }

    @Test
    void executionRequiresProductionInstructions() throws Exception {
        FresnelJobExecutionResult source = executor.execute(
                Files.readAllBytes(repositoryFile(
                        "docs/jobs/zone-plate/on-axis.fresnel")),
                (artifact, content) -> {});
        FresnelJobDocument job = source.job();
        FresnelJobDocument withoutProduction = new FresnelJobDocument(
                job.schema(), job.format(), job.formatVersion(), job.plugin(),
                job.parameters(), null, job.provenance());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(withoutProduction, (artifact, content) -> {}));
        assertTrue(error.getMessage().contains("production plan"));
    }

    @Test
    void directorySinkNeverWritesOutsideItsSelectedRoot() {
        DirectoryFresnelJobOutputSink sink = new DirectoryFresnelJobOutputSink(tempDir);
        GeneratedArtifact artifact = new GeneratedArtifact(
                "escape", "../escape.png", "image/png", 1,
                "0".repeat(64), 1, 1, 1200.0);

        assertThrows(IllegalArgumentException.class,
                () -> sink.write(artifact, new byte[]{1}));
    }

    @Test
    void textualArtifactHashIgnoresPlatformLineEndings() throws Exception {
        String unix = FresnelJobExecutor.normalizedSha256(
                "image/svg+xml", "a\nb\n".getBytes(StandardCharsets.UTF_8), null);
        String windows = FresnelJobExecutor.normalizedSha256(
                "image/svg+xml", "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8), null);
        assertEquals(unix, windows);
    }

    private static FresnelJobDocument.ProductionOutput output(
            String format,
            String filename,
            String sheet,
            Double scale) {
        return new FresnelJobDocument.ProductionOutput(
                format + "-output", format, filename, sheet, scale, null);
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
