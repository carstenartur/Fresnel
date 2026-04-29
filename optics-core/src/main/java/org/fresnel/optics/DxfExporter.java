package org.fresnel.optics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * DXF (AutoCAD Drawing Interchange File) export for fabrication tools.
 *
 * <p>Emits a minimal AutoCAD R12 ASCII DXF containing only an {@code ENTITIES}
 * section. Most CAM software (LightBurn, RDWorks, Inkscape, QCAD, LibreCAD,
 * EagleCAD, KiCad) accepts this minimal form. Coordinates are in millimetres,
 * matching the rest of the optics core.
 *
 * <p>For an on-axis circular zone plate the boundary of every Fresnel zone is
 * emitted as a full {@code CIRCLE} entity (group code 0/CIRCLE) with centre at
 * the origin and radius {@code r_n = √(n·λ·f)}. The aperture-clipping circle
 * is also emitted as the outermost circle. This is the most useful form for
 * laser cutters and pen plotters, which only ever follow path outlines and do
 * not fill regions.
 *
 * <p>For arbitrary masks (off-axis, hex, foil, hologram) there is no concise
 * vector representation; clients should use SVG or PDF in those cases.
 */
public final class DxfExporter {

    private DxfExporter() {}

    /**
     * Write zone-boundary circles of an on-axis zone plate as DXF.
     *
     * <p>Off-axis offsets in {@code p} are honoured by translating the centre.
     * The {@code maskType} and {@code polarity} fields do not affect the output:
     * a CAM operator typically chooses cut/engrave layers from the resulting
     * geometry separately.
     */
    public static void writeZonePlate(SingleZonePlateParameters p, OutputStream out) throws IOException {
        double D = p.apertureDiameterMm();
        double f = p.focalLengthMm();
        double lambdaMm = Units.nmToMm(p.wavelengthNm());
        double R = D / 2.0;
        double cx = p.targetOffsetXmm();
        double cy = p.targetOffsetYmm();
        // Number of zones n_max = R² / (λ·f)
        int nMax = (int) Math.floor((R * R) / (lambdaMm * f));
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writeHeader(w);
            // Aperture-clipping outer circle.
            writeCircle(w, cx, cy, R);
            // Zone boundaries n = 1..nMax (skip any that exceed the aperture).
            for (int n = 1; n <= nMax; n++) {
                double rn = Math.sqrt(n * lambdaMm * f);
                if (rn > R) break;
                writeCircle(w, cx, cy, rn);
            }
            writeFooter(w);
        }
    }

    public static byte[] toDxfBytes(SingleZonePlateParameters p) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeZonePlate(p, baos);
        return baos.toByteArray();
    }

    private static void writeHeader(Writer w) throws IOException {
        // Minimal DXF: just an ENTITIES section. AutoCAD R12-compatible.
        w.write("0\nSECTION\n2\nENTITIES\n");
    }

    private static void writeFooter(Writer w) throws IOException {
        w.write("0\nENDSEC\n0\nEOF\n");
    }

    private static void writeCircle(Writer w, double cx, double cy, double r) throws IOException {
        // Group codes: 0=entity, 8=layer, 10/20/30=centre x/y/z, 40=radius.
        w.write(String.format(Locale.ROOT,
                "0\nCIRCLE\n8\n0\n10\n%.6f\n20\n%.6f\n30\n0.0\n40\n%.6f\n",
                cx, cy, r));
    }
}
