package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableLineGratingTest {

    @Test
    void everyProgressionReachesConfiguredEndpointPitchesInBothDirections() {
        for (GratingProgression progression : GratingProgression.values()) {
            VariableLineGratingParameters normal = parameters(
                    LineOrientation.VERTICAL, progression, ProgressionDirection.NORMAL, 500, 40, 600);
            assertEquals(0.5, VariableLineGratingModel.pitchMmAtNormalized(normal, 0), 1e-12);
            assertEquals(0.04, VariableLineGratingModel.pitchMmAtNormalized(normal, 1), 1e-12);

            VariableLineGratingParameters reversed = parameters(
                    LineOrientation.VERTICAL, progression, ProgressionDirection.REVERSED, 500, 40, 600);
            assertEquals(0.04, VariableLineGratingModel.pitchMmAtNormalized(reversed, 0), 1e-12);
            assertEquals(0.5, VariableLineGratingModel.pitchMmAtNormalized(reversed, 1), 1e-12);
        }
    }

    @Test
    void constantPitchPhaseIsTheIntegratedSpatialFrequency() {
        VariableLineGratingParameters p = parameters(
                LineOrientation.VERTICAL,
                GratingProgression.LINEAR_PITCH,
                ProgressionDirection.NORMAL,
                1000,
                1000,
                600);
        assertEquals(1.0, VariableLineGratingModel.cyclesAt(p, 1.0), 1e-12);
        assertEquals(p.activeProgressionLengthMm(),
                VariableLineGratingModel.nominalCycleCount(p), 1e-9);
    }

    @Test
    void verticalAndHorizontalRasterFamiliesAreExclusiveAndTranspose() {
        VariableLineGratingParameters vertical = tinyRaster(LineOrientation.VERTICAL);
        VariableLineGratingParameters horizontal = tinyRaster(LineOrientation.HORIZONTAL);
        MonochromeRaster vr = VariableLineGratingRasterizer.rasterize(vertical, 100, 100);
        MonochromeRaster hr = VariableLineGratingRasterizer.rasterize(horizontal, 100, 100);

        assertEquals(vr.widthDots(), hr.heightDots());
        assertEquals(vr.heightDots(), hr.widthDots());
        for (int y = 0; y < vr.heightDots(); y++) {
            for (int x = 0; x < vr.widthDots(); x++) {
                assertEquals(vr.isBlack(x, y), hr.isBlack(y, x),
                        "orientation transpose mismatch at " + x + "," + y);
            }
        }
        assertEquals(vr.isBlack(2, 2), vr.isBlack(2, 30),
                "vertical-line membership must not vary along page Y");
        assertEquals(hr.isBlack(2, 2), hr.isBlack(30, 2),
                "horizontal-line membership must not vary along page X");
    }

    @Test
    void selectedDeviceAxisFollowsOrientationAndProfileMapping() {
        PrinterRasterProfile asymmetric = new PrinterRasterProfile(
                "asymmetric-test",
                1,
                PrinterLanguageDialect.PCL_5E,
                600,
                1200,
                10000,
                10000,
                0,
                0,
                10000,
                10000,
                "TEST",
                PclPageOrientation.PORTRAIT,
                DeviceAxis.Y,
                DeviceAxis.X,
                Set.of(PclCompression.NONE));

        VariableLineGratingAnalysis.Result vertical = VariableLineGratingAnalysis.analyze(
                parameters(LineOrientation.VERTICAL, GratingProgression.LINEAR_PITCH,
                        ProgressionDirection.NORMAL, 500, 40, 300),
                asymmetric);
        assertEquals(DeviceAxis.Y, vertical.testedDeviceAxis());
        assertEquals(1200, vertical.selectedAxisDpi());

        VariableLineGratingAnalysis.Result horizontal = VariableLineGratingAnalysis.analyze(
                parameters(LineOrientation.HORIZONTAL, GratingProgression.LINEAR_PITCH,
                        ProgressionDirection.NORMAL, 500, 40, 300),
                asymmetric);
        assertEquals(DeviceAxis.X, horizontal.testedDeviceAxis());
        assertEquals(600, horizontal.selectedAxisDpi());
    }

    @Test
    void intentionalUndersamplingProducesWarningsInsteadOfRejectingTheDesign() {
        VariableLineGratingParameters p = parameters(
                LineOrientation.VERTICAL,
                GratingProgression.LOGARITHMIC_PITCH,
                ProgressionDirection.NORMAL,
                500,
                5,
                300);
        DesignValidationReport report = VariableLineGratingValidation.report(p);
        assertTrue(report.valid());
        assertTrue(report.findings().stream().anyMatch(finding ->
                finding.code().equals("SEVERE_UNDERSAMPLING")
                        && finding.severity() == ValidationSeverity.WARNING));
        assertFalse(report.findings().stream().anyMatch(finding ->
                finding.severity() == ValidationSeverity.ERROR));
    }

    @Test
    void tiffRowsRoundTripExactly() {
        byte[] source = new byte[] {
                0, 0, 0, 0, 1, 2, 3, 4, 4, 4, 4, 4,
                (byte) 0xff, (byte) 0xfe, (byte) 0xfd, 7, 7
        };
        byte[] compressed = PclExporter.tiffCompress(source);
        assertArrayEquals(source, PclExporter.tiffDecompress(compressed, source.length));
    }

    @Test
    void pclGoldenBytesAreStableAndContainBoundedTrustedCommands() throws Exception {
        VariableLineGratingParameters p = tinyRaster(LineOrientation.VERTICAL);
        PrinterRasterProfile profile = new PrinterRasterProfile(
                "golden-test",
                1,
                PrinterLanguageDialect.PCL_5E,
                100,
                100,
                100,
                100,
                0,
                0,
                100,
                100,
                "A4",
                PclPageOrientation.PORTRAIT,
                DeviceAxis.X,
                DeviceAxis.Y,
                Set.of(PclCompression.NONE, PclCompression.TIFF));

        byte[] first = PclExporter.toPclBytes(p, profile, PclCompression.TIFF);
        byte[] second = PclExporter.toPclBytes(p, profile, PclCompression.TIFF);
        assertArrayEquals(first, second);
        assertEquals(
                "be1170d7cbb4d9b9376075d23a701605a6ea5cf4c540106bba465d1f6a5f7594",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first)));

        String commands = new String(first, StandardCharsets.ISO_8859_1);
        assertTrue(commands.contains("\u001bE"));
        assertTrue(commands.contains("\u001b&l26A"));
        assertTrue(commands.contains("\u001b*t100R"));
        assertTrue(commands.contains("\u001b*b2M"));
        assertTrue(commands.endsWith("\u001bE"));
    }

    @Test
    void svgRetainsPhysicalSheetDimensionsAndOrientationMetadata() {
        VariableLineGratingParameters p = parameters(
                LineOrientation.HORIZONTAL,
                GratingProgression.LINEAR_SPATIAL_FREQUENCY,
                ProgressionDirection.NORMAL,
                500,
                40,
                300);
        String svg = new String(VariableLineGratingSvgExporter.toSvgBytes(p), StandardCharsets.UTF_8);
        assertTrue(svg.contains("width=\"190mm\""));
        assertTrue(svg.contains("height=\"277mm\""));
        assertTrue(svg.contains("orientation=HORIZONTAL"));
        assertTrue(svg.contains("PRINT 100%"));
    }

    private static VariableLineGratingParameters tinyRaster(LineOrientation orientation) {
        return new VariableLineGratingParameters(
                12.7,
                12.7,
                orientation,
                2540,
                2540,
                GratingProgression.LINEAR_PITCH,
                ProgressionDirection.NORMAL,
                0.5,
                0,
                Polarity.POSITIVE,
                0,
                1,
                false,
                AxisQuantity.PITCH_UM,
                2,
                false,
                0,
                100);
    }

    private static VariableLineGratingParameters parameters(
            LineOrientation orientation,
            GratingProgression progression,
            ProgressionDirection direction,
            double startPitchUm,
            double endPitchUm,
            double dpi) {
        return new VariableLineGratingParameters(
                190,
                277,
                orientation,
                startPitchUm,
                endPitchUm,
                progression,
                direction,
                0.5,
                0,
                Polarity.POSITIVE,
                5,
                14,
                true,
                AxisQuantity.PITCH_UM,
                9,
                true,
                5,
                dpi);
    }
}
