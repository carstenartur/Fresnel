package org.fresnel.optics;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * PNG export with embedded physical-pixel metadata (pHYs chunk) so prints come
 * out at the correct DPI.
 */
public final class PngExporter {

    private PngExporter() {}

    /** Write the rendered zone plate as PNG to the given stream, embedding DPI metadata. */
    public static void writePng(RenderResult result, double dpi, OutputStream out) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            BufferedImage img = result.image();
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    new ImageTypeSpecifier(img), param);
            addDpiMetadata(metadata, dpi);
            writer.write(null, new IIOImage(img, null, metadata), param);
        } finally {
            writer.dispose();
        }
    }

    /** Convenience: render to PNG byte array. */
    public static byte[] toPngBytes(RenderResult result, double dpi) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writePng(result, dpi, baos);
        return baos.toByteArray();
    }

    private static void addDpiMetadata(IIOMetadata metadata, double dpi) throws IOException {
        // pixels per millimeter
        double dotsPerMm = dpi / Units.INCH_MM;
        // PNG pHYs uses pixels-per-meter when unit specifier is 1 (meters)
        long pixelsPerMeter = Math.round(dotsPerMm * 1000.0);

        String formatName = "javax_imageio_png_1.0";
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formatName);
        IIOMetadataNode phys = new IIOMetadataNode("pHYs");
        phys.setAttribute("pixelsPerUnitXAxis", Long.toString(pixelsPerMeter));
        phys.setAttribute("pixelsPerUnitYAxis", Long.toString(pixelsPerMeter));
        phys.setAttribute("unitSpecifier", "meter");
        root.appendChild(phys);
        metadata.mergeTree(formatName, root);
    }
}
