package org.fresnel.optics;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void accumulatedPhaseIsStrictlyMonotonicForEverySupportedLawAndDirection() {
        for (GratingProgression progression : GratingProgression.values()) {
            for (ProgressionDirection direction : ProgressionDirection.values()) {
                VariableLineGratingParameters p = parameters(
                        LineOrientation.VERTICAL, progression, direction, 500, 40, 600);
                double previous = VariableLineGratingModel.cyclesAt(p, 0.0);
                for (int i = 1; i <= 1000; i++) {
                    double position = p.activeProgressionLengthMm() * i / 1000.0;
                    double current = VariableLineGratingModel.cyclesAt(p, position);
                    assertTrue(current > previous,
                            progression + " / " + direction + " lost monotonic phase at " + position);
                    previous = current;
                }
            }
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
    void dutyCycleBoundaryAndPolarityAreExact() {
        VariableLineGratingParameters positive = constantPitchNoAnnotations(Polarity.POSITIVE);
        assertTrue(VariableLineGratingModel.isOpaque(positive, 0.499999, 5.0));
        assertFalse(VariableLineGratingModel.isOpaque(positive, 0.5, 5.0));
        assertFalse(VariableLineGratingModel.isOpaque(positive, 0.999999, 5.0));
        assertTrue(VariableLineGratingModel.isOpaque(positive, 1.0, 5.0));

        VariableLineGratingParameters negative = constantPitchNoAnnotations(Polarity.NEGATIVE);
        assertFalse(VariableLineGratingModel.isOpaque(negative, 0.499999, 5.0));
        assertTrue(VariableLineGratingModel.isOpaque(negative, 0.5, 5.0));
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
    void highResolutionPhysicalDesignRemainsValidUntilAResourceIntensiveRasterIsRequested() {
        VariableLineGratingParameters p = assertDoesNotThrow(() -> new VariableLineGratingParameters(
                190,
                277,
                LineOrientation.VERTICAL,
                500,
                40,
                GratingProgression.LINEAR_SPATIAL_FREQUENCY,
                ProgressionDirection.NORMAL,
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
                2400));
        assertThrows(IllegalArgumentException.class,
                () -> VariableLineGratingRasterizer.rasterize(p, p.dpi(), p.dpi()));
        assertTrue(VariableLineGratingSvgExporter.toSvgBytes(p).length > 100);
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
    void emittedPclRasterDecodesDotForDotForCompressedAndDiagnosticModes() {
        VariableLineGratingParameters p = tinyRaster(LineOrientation.VERTICAL);
        PrinterRasterProfile profile = goldenProfile(PclPageOrientation.PORTRAIT);
        MonochromeRaster expected = VariableLineGratingRasterizer.rasterize(p, 100, 100);

        int paddingBits = expected.rowBytes() * 8 - expected.widthDots();
        assertTrue(paddingBits > 0, "fixture must exercise row padding");
        int paddingMask = (1 << paddingBits) - 1;
        for (int y = 0; y < expected.heightDots(); y++) {
            byte[] row = expected.row(y);
            assertEquals(0, row[row.length - 1] & paddingMask,
                    "unused row-padding bits must remain clear");
        }

        for (PclCompression compression : PclCompression.values()) {
            byte[] pcl = PclExporter.toPclBytes(p, profile, compression);
            byte[][] decodedRows = decodePclRows(
                    pcl, expected.heightDots(), expected.rowBytes(), compression);
            assertEquals(expected.heightDots(), decodedRows.length);
            for (int y = 0; y < expected.heightDots(); y++) {
                assertArrayEquals(expected.row(y), decodedRows[y],
                        compression + " raster row differs at y=" + y);
            }
        }
    }

    @Test
    void pclGoldenBytesAreStableAndContainBoundedTrustedCommands() throws Exception {
        VariableLineGratingParameters p = tinyRaster(LineOrientation.VERTICAL);
        PrinterRasterProfile profile = goldenProfile(PclPageOrientation.PORTRAIT);

        byte[] first = PclExporter.toPclBytes(p, profile, PclCompression.TIFF);
        byte[] second = PclExporter.toPclBytes(p, profile, PclCompression.TIFF);
        assertArrayEquals(first, second);
        assertEquals(
                "be1170d7cbb4d9b9376075d23a701605a6ea5cf4c540106bba465d1f6a5f7594",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first)));

        String commands = new String(first, StandardCharsets.ISO_8859_1);
        assertTrue(commands.contains("\u001bE"));
        assertTrue(commands.contains("\u001b&l26A"));
        assertTrue(commands.contains("\u001b&l0O"));
        assertTrue(commands.contains("\u001b*t100R"));
        assertTrue(commands.contains("\u001b*b2M"));
        assertTrue(commands.endsWith("\u001bE"));
    }

    @Test
    void pageOrientationCommandAndAxisMappingRemainExplicit() {
        VariableLineGratingParameters p = tinyRaster(LineOrientation.HORIZONTAL);
        PrinterRasterProfile landscape = goldenProfile(PclPageOrientation.LANDSCAPE);
        byte[] pcl = PclExporter.toPclBytes(p, landscape, PclCompression.NONE);
        assertTrue(new String(pcl, StandardCharsets.ISO_8859_1).contains("\u001b&l1O"));
        assertEquals(DeviceAxis.Y, landscape.testedDeviceAxis(LineOrientation.HORIZONTAL));
    }

    @Test
    void unsupportedProfileMediaFailsInsteadOfFallingBackToAHiddenCommand() {
        PrinterRasterProfile unsupported = new PrinterRasterProfile(
                "letter-test",
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
                "LETTER",
                PclPageOrientation.PORTRAIT,
                DeviceAxis.X,
                DeviceAxis.Y,
                Set.of(PclCompression.NONE));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PclExporter.toPclBytes(
                        tinyRaster(LineOrientation.VERTICAL), unsupported, PclCompression.NONE));
        assertTrue(error.getMessage().contains("unsupported PCL 5e media size"));
    }

    @Test
    void rasterSvgAndFitPdfRetainThePhysicalSheetDimensions() throws Exception {
        VariableLineGratingParameters p = tinyRaster(LineOrientation.HORIZONTAL);
        RenderResult rendered = VariableLineGratingRenderer.render(p);
        assertEquals(50, rendered.image().getWidth());
        assertEquals(50, rendered.image().getHeight());
        assertEquals(12.7, rendered.widthMm(), 1e-9);
        assertEquals(12.7, rendered.heightMm(), 1e-9);

        String svg = new String(VariableLineGratingSvgExporter.toSvgBytes(p), StandardCharsets.UTF_8);
        assertTrue(svg.contains("width=\"12.7mm\""));
        assertTrue(svg.contains("height=\"12.7mm\""));
        assertTrue(svg.contains("orientation=HORIZONTAL"));
        assertTrue(svg.contains("PRINT 100%"));

        byte[] pdf = PdfExporter.toPdfBytes(rendered, PdfExporter.SheetSize.FIT);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            assertEquals(12.7 * 72.0 / Units.INCH_MM,
                    document.getPage(0).getMediaBox().getWidth(), 0.5);
            assertEquals(12.7 * 72.0 / Units.INCH_MM,
                    document.getPage(0).getMediaBox().getHeight(), 0.5);
        }
    }

    private static byte[][] decodePclRows(
            byte[] pcl,
            int rowCount,
            int rowBytes,
            PclCompression compression) {
        byte[] rasterStart = command("*r1A");
        int offset = indexOf(pcl, rasterStart);
        assertTrue(offset >= 0, "PCL raster-start command missing");
        offset += rasterStart.length;

        byte[][] rows = new byte[rowCount][];
        for (int y = 0; y < rowCount; y++) {
            assertEquals(0x1b, pcl[offset++] & 0xff, "row command ESC missing at y=" + y);
            assertEquals('*', pcl[offset++]);
            assertEquals('b', pcl[offset++]);
            int encodedLength = 0;
            int digits = 0;
            while (offset < pcl.length && pcl[offset] >= '0' && pcl[offset] <= '9') {
                encodedLength = encodedLength * 10 + (pcl[offset++] - '0');
                digits++;
            }
            assertTrue(digits > 0, "row byte count missing at y=" + y);
            assertEquals('W', pcl[offset++], "row transfer terminator missing at y=" + y);
            assertTrue(offset + encodedLength <= pcl.length, "truncated PCL row at y=" + y);
            byte[] encoded = Arrays.copyOfRange(pcl, offset, offset + encodedLength);
            offset += encodedLength;
            rows[y] = compression == PclCompression.NONE
                    ? encoded
                    : PclExporter.tiffDecompress(encoded, rowBytes);
            assertEquals(rowBytes, rows[y].length, "decoded row length differs at y=" + y);
        }

        byte[] rasterEnd = command("*rB");
        assertTrue(startsWith(pcl, offset, rasterEnd), "PCL raster-end command missing");
        offset += rasterEnd.length;
        assertEquals(0x0c, pcl[offset++] & 0xff, "form feed missing");
        byte[] finalReset = command("E");
        assertTrue(startsWith(pcl, offset, finalReset), "final PCL reset missing");
        offset += finalReset.length;
        assertEquals(pcl.length, offset, "unexpected bytes after final reset");
        return rows;
    }

    private static byte[] command(String value) {
        byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[ascii.length + 1];
        result[0] = 0x1b;
        System.arraycopy(ascii, 0, result, 1, ascii.length);
        return result;
    }

    private static int indexOf(byte[] source, byte[] target) {
        for (int i = 0; i <= source.length - target.length; i++) {
            if (startsWith(source, i, target)) return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] source, int offset, byte[] target) {
        if (offset < 0 || offset + target.length > source.length) return false;
        for (int i = 0; i < target.length; i++) {
            if (source[offset + i] != target[i]) return false;
        }
        return true;
    }

    private static PrinterRasterProfile goldenProfile(PclPageOrientation orientation) {
        return new PrinterRasterProfile(
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
                orientation,
                DeviceAxis.X,
                DeviceAxis.Y,
                Set.of(PclCompression.NONE, PclCompression.TIFF));
    }

    private static VariableLineGratingParameters constantPitchNoAnnotations(Polarity polarity) {
        return new VariableLineGratingParameters(
                10,
                10,
                LineOrientation.VERTICAL,
                1000,
                1000,
                GratingProgression.LINEAR_PITCH,
                ProgressionDirection.NORMAL,
                0.5,
                0,
                polarity,
                0,
                1,
                false,
                AxisQuantity.PITCH_UM,
                2,
                false,
                0,
                100);
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
