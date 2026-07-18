package org.fresnel.backend.api;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** Safely inspects and decodes the bounded PNG/JPEG source in a Hologram request. */
final class HologramImageDecoder {

    static final int MAX_BASE64_CHARACTERS = 8 * 1024 * 1024;
    static final int MAX_SOURCE_SIDE = 8192;
    static final long MAX_SOURCE_PIXELS = 16L * 1024L * 1024L;

    private static final Set<String> ALLOWED_FORMATS = Set.of("png", "jpeg", "jpg");

    private HologramImageDecoder() {}

    /**
     * Preflights any recognizable image without allocating its pixel raster.
     *
     * <p>Legacy structural-validation fixtures may contain short Base64 placeholders
     * that are not images. Those remain compatible here and still fail if a render
     * path later attempts strict decoding. Recognizable image formats, however, are
     * always checked for their format and declared dimensions.</p>
     */
    static void validate(String encoded) throws IOException {
        if (encoded == null || encoded.isBlank()) return;
        String base64 = checkedBase64Payload(encoded);
        final byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ignored) {
            return;
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(raw))) {
            if (input == null) return;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return;

            ImageReader reader = readers.next();
            try {
                requireAllowedFormat(reader);
                reader.setInput(input, true, true);
                requireBoundedDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    /** Decodes pixels only after the reader has reported acceptable dimensions. */
    static BufferedImage decode(String encoded) throws IOException {
        try (InspectedImage inspected = inspectStrict(encoded)) {
            BufferedImage image = inspected.reader().read(0);
            if (image == null) {
                throw new IllegalArgumentException(
                        "Hologram target image could not be decoded");
            }
            requireBoundedDimensions(image.getWidth(), image.getHeight());
            return image;
        }
    }

    private static InspectedImage inspectStrict(String encoded) throws IOException {
        byte[] raw = decodeBase64Strict(encoded);
        ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(raw));
        if (input == null) {
            throw new IllegalArgumentException(
                    "Hologram target image could not be inspected");
        }

        ImageReader reader = null;
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException(
                        "Hologram target image is not a supported PNG or JPEG");
            }
            reader = readers.next();
            requireAllowedFormat(reader);
            reader.setInput(input, true, true);
            requireBoundedDimensions(reader.getWidth(0), reader.getHeight(0));
            return new InspectedImage(input, reader);
        } catch (IOException | RuntimeException e) {
            if (reader != null) reader.dispose();
            input.close();
            throw e;
        }
    }

    private static byte[] decodeBase64Strict(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Hologram target image must not be empty");
        }
        String base64 = checkedBase64Payload(encoded);
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Hologram target image is not valid Base64", e);
        }
    }

    private static String checkedBase64Payload(String encoded) {
        String base64 = stripDataUrlPrefix(encoded);
        if (base64.length() > MAX_BASE64_CHARACTERS) {
            throw new IllegalArgumentException(
                    "targetImageBase64 too large (>" + MAX_BASE64_CHARACTERS
                            + " characters); resize before upload");
        }
        return base64;
    }

    private static void requireAllowedFormat(ImageReader reader) throws IOException {
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!ALLOWED_FORMATS.contains(format)) {
            throw new IllegalArgumentException(
                    "Hologram target image must be PNG or JPEG, not " + format);
        }
    }

    private static void requireBoundedDimensions(int width, int height) {
        long pixels = (long) width * (long) height;
        if (width < 1 || height < 1
                || width > MAX_SOURCE_SIDE
                || height > MAX_SOURCE_SIDE
                || pixels > MAX_SOURCE_PIXELS) {
            throw new IllegalArgumentException(
                    "Hologram target image dimensions " + width + " x " + height
                            + " exceed the limit of " + MAX_SOURCE_SIDE
                            + " pixels per side and " + MAX_SOURCE_PIXELS
                            + " total pixels");
        }
    }

    private static String stripDataUrlPrefix(String encoded) {
        if (!encoded.startsWith("data:")) return encoded;
        int comma = encoded.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException(
                    "Hologram target image data URL is malformed");
        }
        String header = encoded.substring(5, comma).toLowerCase(Locale.ROOT);
        if (!(header.startsWith("image/png;") || header.startsWith("image/jpeg;"))
                || !header.contains(";base64")) {
            throw new IllegalArgumentException(
                    "Hologram target image data URL must contain Base64 PNG or JPEG data");
        }
        return encoded.substring(comma + 1);
    }

    private record InspectedImage(ImageInputStream input, ImageReader reader)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            reader.dispose();
            input.close();
        }
    }
}
