package org.fresnel.backend.measurement;

import org.fresnel.measurement.BosTarget;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/** Lossless PNG encoder that preserves the target's intended physical print scale. */
public final class BosTargetPngEncoder {

    private static final String PNG_METADATA_FORMAT = "javax_imageio_png_1.0";

    private BosTargetPngEncoder() {}

    public static byte[] encode(BosTarget target) {
        if (target == null) throw new IllegalArgumentException("target must not be null");

        BufferedImage image = new BufferedImage(
                target.parameters().widthPx(),
                target.parameters().heightPx(),
                BufferedImage.TYPE_BYTE_GRAY);
        byte[] raster = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        byte[] pixels = target.grayscalePixels();
        if (raster.length != pixels.length) {
            throw new IllegalStateException("unexpected grayscale raster layout");
        }
        System.arraycopy(pixels, 0, raster, 0, pixels.length);

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IllegalStateException("PNG writer unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(image), writeParam);
            addPhysicalResolution(metadata, target.parameters().intendedDpi());
            writer.write(null, new IIOImage(image, null, metadata), writeParam);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not encode BOS target PNG", exception);
        } finally {
            writer.dispose();
        }
    }

    private static void addPhysicalResolution(IIOMetadata metadata, double dpi) throws IOException {
        int pixelsPerMeter = Math.toIntExact(Math.round(dpi / 0.0254));
        IIOMetadataNode root = new IIOMetadataNode(PNG_METADATA_FORMAT);
        IIOMetadataNode physical = new IIOMetadataNode("pHYs");
        physical.setAttribute("pixelsPerUnitXAxis", Integer.toString(pixelsPerMeter));
        physical.setAttribute("pixelsPerUnitYAxis", Integer.toString(pixelsPerMeter));
        physical.setAttribute("unitSpecifier", "meter");
        root.appendChild(physical);
        metadata.mergeTree(PNG_METADATA_FORMAT, root);
    }
}
