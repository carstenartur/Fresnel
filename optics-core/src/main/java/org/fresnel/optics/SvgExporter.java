package org.fresnel.optics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * SVG export.
 *
 * <p>Two strategies:
 * <ul>
 *   <li>{@link #writeSvgZonePlate(SingleZonePlateParameters, OutputStream)} — true vector
 *       concentric rings for an on-axis circular zone plate (zone radii are
 *       {@code r_n = √(n·λ·f + (n·λ/2)²)} ≈ {@code √(n·λ·f)}). Plotter-friendly,
 *       small file size, scale-independent.</li>
 *   <li>{@link #writeSvgRaster(RenderResult, double, OutputStream)} — wraps any raster
 *       as a base64 PNG inside an SVG {@code <image>} element with explicit
 *       physical {@code width}/{@code height} in millimetres. Works for arbitrary
 *       designs (off-axis, hex macro cell, window foil, hologram).</li>
 * </ul>
 */
public final class SvgExporter {

    private SvgExporter() {}

    /**
     * True-vector SVG of an on-axis circular zone plate. Off-axis parameters are
     * ignored — for off-axis, use {@link #writeSvgRaster(RenderResult, double, OutputStream)}.
     */
    public static void writeSvgZonePlate(SingleZonePlateParameters p, OutputStream out) throws IOException {
        double D = p.apertureDiameterMm();
        double f = p.focalLengthMm();
        double lambdaMm = Units.nmToMm(p.wavelengthNm());
        double R = D / 2.0;
        // Number of zones n_max = R² / (λ·f)
        int nMax = (int) Math.floor((R * R) / (lambdaMm * f));
        boolean positive = p.polarity() == Polarity.POSITIVE;
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write(String.format(Locale.ROOT,
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" "
                            + "width=\"%.4fmm\" height=\"%.4fmm\" viewBox=\"%.4f %.4f %.4f %.4f\">\n",
                    D, D, -R, -R, D, D));
            // Background = aperture-clipping circle filled with the OFF colour.
            String onFill  = positive ? "#ffffff" : "#000000";
            String offFill = positive ? "#000000" : "#ffffff";
            w.write(String.format(Locale.ROOT,
                    "  <circle cx=\"0\" cy=\"0\" r=\"%.6f\" fill=\"%s\"/>\n", R, offFill));
            // Zones n = 1..nMax. Zone n is bounded by r_{n-1} and r_n. ON for odd n in positive polarity.
            for (int n = nMax; n >= 1; n--) {
                double rn = Math.sqrt(n * lambdaMm * f);
                if (rn > R) continue;
                boolean on = (n % 2) == 1;          // first zone (n=1) is ON for positive polarity
                String fill = on ? onFill : offFill;
                w.write(String.format(Locale.ROOT,
                        "  <circle cx=\"0\" cy=\"0\" r=\"%.6f\" fill=\"%s\"/>\n", rn, fill));
            }
            w.write("</svg>\n");
        }
    }

    public static byte[] toSvgZonePlateBytes(SingleZonePlateParameters p) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeSvgZonePlate(p, baos);
        return baos.toByteArray();
    }

    /**
     * Embed a raster {@link RenderResult} inside an SVG {@code <image>} as a
     * base64-encoded PNG, sized to its physical extent in millimetres.
     */
    public static void writeSvgRaster(RenderResult r, double dpi, OutputStream out) throws IOException {
        BufferedImage img = r.image();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        // Use plain PNG (without DPI metadata — the SVG provides physical size).
        ImageIO.write(img, "png", png);
        String b64 = Base64.getEncoder().encodeToString(png.toByteArray());
        double wMm = r.widthMm();
        double hMm = r.heightMm();
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write(String.format(Locale.ROOT,
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                            + "xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\" "
                            + "width=\"%.4fmm\" height=\"%.4fmm\" viewBox=\"0 0 %d %d\">\n",
                    wMm, hMm, img.getWidth(), img.getHeight()));
            w.write(String.format(Locale.ROOT,
                    "  <image x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" "
                            + "image-rendering=\"pixelated\" "
                            + "xlink:href=\"data:image/png;base64,%s\"/>\n",
                    img.getWidth(), img.getHeight(), b64));
            w.write(String.format(Locale.ROOT, "  <!-- DPI: %.2f -->\n", dpi));
            w.write("</svg>\n");
        }
    }

    public static byte[] toSvgRasterBytes(RenderResult r, double dpi) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeSvgRaster(r, dpi, baos);
        return baos.toByteArray();
    }
}
