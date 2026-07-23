package org.fresnel.optics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Deterministic PCL 5e monochrome raster exporter.
 *
 * <p>The caller supplies only a trusted profile ID and bounded compression enum;
 * arbitrary preambles or escape sequences are deliberately impossible.</p>
 */
public final class PclExporter {

    public static final String MEDIA_TYPE = "application/vnd.hp-pcl";
    private static final byte ESC = 0x1b;

    private PclExporter() {}

    public static byte[] toPclBytes(
            VariableLineGratingParameters parameters,
            PrinterRasterProfile profile,
            PclCompression compression) {
        if (profile == null) throw new IllegalArgumentException("printer profile must not be null");
        if (compression == null) compression = PclCompression.TIFF;
        if (profile.dialect() != PrinterLanguageDialect.PCL_5E) {
            throw new IllegalArgumentException("unsupported printer dialect: " + profile.dialect());
        }
        if (!profile.compressionModes().contains(compression)) {
            throw new IllegalArgumentException(
                    "printer profile " + profile.id() + " does not support " + compression);
        }
        // The initial PCL 5e dialect uses one raster-resolution command. Profiles may
        // describe asymmetric devices for analysis, but export rejects that combination.
        if (profile.dpiForPageX() != profile.dpiForPageY()) {
            throw new IllegalArgumentException(
                    "PCL_5E export currently requires equal page-X and page-Y raster DPI; profile "
                            + profile.id() + " maps to " + profile.dpiForPageX() + "x"
                            + profile.dpiForPageY() + " DPI");
        }

        MonochromeRaster raster = VariableLineGratingRasterizer.rasterize(
                parameters, profile.dpiForPageX(), profile.dpiForPageY());
        verifyFits(raster, profile);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                Math.max(4096, raster.data().length / 2));
        command(out, "E");
        command(out, "&l" + profile.mediaSizeCommandValue() + "A");
        command(out, "&l" + profile.pageOrientation().commandValue() + "O");
        command(out, "&u" + profile.dpiForPageX() + "D");
        command(out, "*t" + profile.dpiForPageX() + "R");
        command(out, "*p" + profile.printableOriginXDots() + "X");
        command(out, "*p" + profile.printableOriginYDots() + "Y");
        command(out, "*r" + raster.widthDots() + "S");
        command(out, "*r" + raster.heightDots() + "T");
        command(out, "*b" + compression.commandValue() + "M");
        command(out, "*r1A");

        for (int y = 0; y < raster.heightDots(); y++) {
            byte[] row = raster.row(y);
            byte[] encoded = compression == PclCompression.NONE ? row : tiffCompress(row);
            command(out, "*b" + encoded.length + "W");
            out.writeBytes(encoded);
        }
        command(out, "*rB");
        out.write(0x0c);
        command(out, "E");
        return out.toByteArray();
    }

    static byte[] tiffCompress(byte[] source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length);
        int i = 0;
        while (i < source.length) {
            int run = repeatedRun(source, i);
            if (run >= 3) {
                int count = Math.min(run, 128);
                out.write(257 - count);
                out.write(source[i] & 0xff);
                i += count;
                continue;
            }

            int literalStart = i;
            i += run;
            while (i < source.length && i - literalStart < 128) {
                int nextRun = repeatedRun(source, i);
                if (nextRun >= 3) break;
                if (i - literalStart + nextRun > 128) break;
                i += nextRun;
            }
            int count = i - literalStart;
            out.write(count - 1);
            out.writeBytes(Arrays.copyOfRange(source, literalStart, i));
        }
        return out.toByteArray();
    }

    static byte[] tiffDecompress(byte[] encoded, int expectedLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expectedLength);
        int i = 0;
        while (i < encoded.length) {
            int control = encoded[i++] & 0xff;
            if (control <= 127) {
                int count = control + 1;
                if (i + count > encoded.length) throw new IllegalArgumentException("truncated TIFF literal");
                out.write(encoded, i, count);
                i += count;
            } else if (control >= 129) {
                int count = 257 - control;
                if (i >= encoded.length) throw new IllegalArgumentException("truncated TIFF run");
                byte value = encoded[i++];
                for (int j = 0; j < count; j++) out.write(value);
            }
        }
        byte[] result = out.toByteArray();
        if (result.length != expectedLength) {
            throw new IllegalArgumentException(
                    "decoded TIFF row has " + result.length + " bytes, expected " + expectedLength);
        }
        return result;
    }

    private static int repeatedRun(byte[] source, int offset) {
        int count = 1;
        while (offset + count < source.length && count < 128
                && source[offset + count] == source[offset]) {
            count++;
        }
        return count;
    }

    private static void verifyFits(MonochromeRaster raster, PrinterRasterProfile profile) {
        if (raster.widthDots() > profile.printableWidthDots()
                || raster.heightDots() > profile.printableHeightDots()) {
            throw new IllegalArgumentException(
                    "grating raster " + raster.widthDots() + "x" + raster.heightDots()
                            + " dots does not fit printable area " + profile.printableWidthDots()
                            + "x" + profile.printableHeightDots() + " of profile " + profile.id());
        }
    }

    private static void command(ByteArrayOutputStream out, String command) {
        out.write(ESC);
        out.writeBytes(command.getBytes(StandardCharsets.US_ASCII));
    }
}
