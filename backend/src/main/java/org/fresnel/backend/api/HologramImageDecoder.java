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

/** Safely decodes the bounded PNG/JPEG source embedded in a Hologram request. */
final class HologramImageDecoder {

    static final int MAX_BASE64_CHARACTERS = 8 * 1024 * 1024;
    static final int MAX_SOURCE_SIDE = 8192;
    static final long MAX_SOURCE_PIXELS = 16L * 1024L * 1024L;

    private static final Set<String> ALLOWED_FORMATS = Set.of("png", "jpeg", "jpg");

    private HologramImageDecoder() {}

    static BufferedImage decode(String encoded) throws IOException {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Hologram target image must not be empty");
        }
        String base64 = stripDataUrlPrefix(encoded);
        if (base64.length() > MAX_BASE64_CHARACTERS) {
            throw new IllegalArgumentException(
                    "targetImageBase64 too large (>" + MAX_BASE64_CHARACTERS
                            + " characters); resize before upload");
        }

        final byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Hologram target image is not valid Base64", e);
        }
        return decodeBytes(raw);
    }

    private static BufferedImage decodeBytes(byte[] raw) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(raw))) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "Hologram target image could not be inspected");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException(
                        "Hologram target image is not a supported PNG or JPEG");
            }

            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!ALLOWED_FORMATS.contains(format)) {
                    throw new IllegalArgumentException(
                            "Hologram target image must be PNG or JPEG, not " + format);
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                requireBoundedDimensions(width, height);

                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException(
                            "Hologram target image could not be decoded");
                }
                requireBoundedDimensions(image.getWidth(), image.getHeight());
                return image;
            } finally {
                reader.dispose();
            }
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
}
