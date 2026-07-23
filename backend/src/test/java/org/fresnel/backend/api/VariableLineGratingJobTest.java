package org.fresnel.backend.api;

import org.fresnel.optics.AxisQuantity;
import org.fresnel.optics.GratingProgression;
import org.fresnel.optics.LineOrientation;
import org.fresnel.optics.Polarity;
import org.fresnel.optics.ProgressionDirection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
class VariableLineGratingJobTest {

    @Autowired FresnelJobService jobService;
    @Autowired FresnelJobExecutor executor;
    @Autowired ObjectMapper mapper;

    @Test
    void separateExampleJobsRestoreTheirExclusiveOrientations() throws Exception {
        FresnelJobDocument vertical = jobService.parseAndNormalize(Files.readAllBytes(
                repositoryFile("examples/variable-line-grating/vertical-lines.fresnel")));
        FresnelJobDocument horizontal = jobService.parseAndNormalize(Files.readAllBytes(
                repositoryFile("examples/variable-line-grating/horizontal-lines.fresnel")));

        assertEquals("variable-line-grating", vertical.plugin().id());
        assertEquals("VERTICAL", vertical.parameters().get("lineOrientation").textValue());
        assertEquals("HORIZONTAL", horizontal.parameters().get("lineOrientation").textValue());
        assertFalse(vertical.parameters().has("horizontalLines"));
        assertFalse(horizontal.parameters().has("verticalLines"));
    }

    @Test
    void jobExecutorProducesEveryAdvertisedGratingFormatIncludingNativePcl() throws Exception {
        VariableLineGratingRequest request = smallRequest(LineOrientation.VERTICAL);
        ObjectNode pclOptions = mapper.createObjectNode();
        pclOptions.put("printerProfileId", "pcl5e-a4-600-portrait-v1");
        pclOptions.put("compression", "TIFF");
        FresnelJobDocument job = new FresnelJobDocument(
                FresnelJobDocument.SCHEMA_URL,
                FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION,
                new FresnelJobDocument.PluginRef("variable-line-grating", 1, "variable-line-grating/1"),
                mapper.valueToTree(request),
                new FresnelJobDocument.ProductionPlan(List.of(
                        output("png", "grating.png", null, null, null),
                        output("svg", "grating.svg", null, null, null),
                        output("pdf", "grating.pdf", "A4", 1.0, null),
                        output("pcl", "grating.pcl", null, null, pclOptions))),
                null);

        Map<String, byte[]> generated = new LinkedHashMap<>();
        FresnelJobExecutionResult result = executor.execute(
                job,
                (artifact, content) -> generated.put(artifact.filename(), content.clone()));

        assertEquals(Set.of("image/png", "image/svg+xml", "application/pdf", "application/vnd.hp-pcl"),
                result.artifacts().stream().map(GeneratedArtifact::mediaType)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, generated.size());
        assertTrue(generated.values().stream().allMatch(content -> content.length > 64));
        byte[] pcl = generated.get("grating.pcl");
        assertEquals(0x1b, pcl[0] & 0xff);
        assertEquals('E', pcl[1]);
    }

    @Test
    void pclJobOptionsCannotInjectPrinterCommands() {
        ObjectNode maliciousOptions = mapper.createObjectNode();
        maliciousOptions.put("preamble", "ESC E");
        FresnelJobDocument job = new FresnelJobDocument(
                FresnelJobDocument.SCHEMA_URL,
                FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION,
                new FresnelJobDocument.PluginRef("variable-line-grating", 1, "variable-line-grating/1"),
                mapper.valueToTree(smallRequest(LineOrientation.HORIZONTAL)),
                new FresnelJobDocument.ProductionPlan(List.of(
                        output("pcl", "grating.pcl", null, null, maliciousOptions))),
                null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(job, (artifact, content) -> {}));
        assertTrue(error.getMessage().contains("Unsupported variable-line grating PCL option"));
    }

    @Test
    void invalidCrossFieldLayoutIsRejectedDuringJobNormalization() {
        VariableLineGratingRequest invalid = new VariableLineGratingRequest(
                20.0,
                20.0,
                LineOrientation.VERTICAL,
                500.0,
                40.0,
                GratingProgression.LINEAR_PITCH,
                ProgressionDirection.NORMAL,
                0.5,
                0.0,
                Polarity.POSITIVE,
                9.0,
                14.0,
                true,
                AxisQuantity.PITCH_UM,
                9,
                true,
                5.0,
                300.0);
        FresnelJobDocument job = new FresnelJobDocument(
                FresnelJobDocument.SCHEMA_URL,
                FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION,
                new FresnelJobDocument.PluginRef("variable-line-grating", 1, "variable-line-grating/1"),
                mapper.valueToTree(invalid),
                null,
                null);

        assertThrows(IllegalArgumentException.class, () -> jobService.normalize(job));
    }

    private static VariableLineGratingRequest smallRequest(LineOrientation orientation) {
        return new VariableLineGratingRequest(
                20.0,
                20.0,
                orientation,
                500.0,
                80.0,
                GratingProgression.LINEAR_SPATIAL_FREQUENCY,
                ProgressionDirection.NORMAL,
                0.5,
                0.0,
                Polarity.POSITIVE,
                2.0,
                4.0,
                true,
                AxisQuantity.DEVICE_DOTS_PER_PERIOD,
                5,
                false,
                0.0,
                150.0);
    }

    private static FresnelJobDocument.ProductionOutput output(
            String format,
            String filename,
            String sheet,
            Double scale,
            ObjectNode options) {
        return new FresnelJobDocument.ProductionOutput(
                format + "-output", format, filename, sheet, scale, options);
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
