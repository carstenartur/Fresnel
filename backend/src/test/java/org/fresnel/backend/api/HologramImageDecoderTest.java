package org.fresnel.backend.api;

import org.fresnel.optics.HologramParameters;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramImageDecoderTest {

    @Test
    void rejectsHugePngDimensionsBeforeAllocatingThePixelRaster() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(
                pngHeaderWithDimensions(20_000, 20_000));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> request(encoded));

        assertTrue(error.getMessage().contains("20,000")
                        || error.getMessage().contains("20000"),
                error::getMessage);
        assertTrue(error.getMessage().contains("dimensions"), error::getMessage);
    }

    @Test
    void acceptsBoundedPngDataUrlsAndNormalizesOnlyAfterInspection() throws Exception {
        String encoded = "data:image/png;base64," + Base64.getEncoder()
                .encodeToString(imageBytes("png"));

        HologramParameters parameters = HologramController.decode(request(encoded));

        assertEquals(16, parameters.targetImage().getWidth());
        assertEquals(16, parameters.targetImage().getHeight());
    }

    @Test
    void preservesLegacyNonImagePlaceholdersUntilARealRenderIsRequested() {
        HologramRequest placeholder = request("AA==");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> HologramController.decode(placeholder));

        assertTrue(error.getMessage().contains("supported PNG or JPEG"), error::getMessage);
    }

    @Test
    void rejectsImageFormatsOutsideThePublicPngJpegContract() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(imageBytes("gif"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> request(encoded));

        assertTrue(error.getMessage().contains("PNG or JPEG"), error::getMessage);
    }

    @Test
    void rejectsNonImageDataUrlsBeforeBase64Decoding() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> request("data:text/plain;base64,SGVsbG8="));

        assertTrue(error.getMessage().contains("PNG or JPEG"), error::getMessage);
    }

    private static HologramRequest request(String encoded) {
        return new HologramRequest(
                encoded,
                16,
                1,
                HologramParameters.OutputType.GREYSCALE_PHASE,
                600.0,
                550.0,
                0.5,
                2.0 * Math.PI);
    }

    private static byte[] imageBytes(String format) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output), "missing ImageIO writer for " + format);
        return output.toByteArray();
    }

    /**
     * Starts from a valid one-pixel PNG, changes only IHDR dimensions and repairs
     * the IHDR CRC. Metadata readers can inspect the dimensions, while a vulnerable
     * implementation would attempt an enormous allocation before discovering that
     * the one-pixel IDAT payload is inconsistent.
     */
    private static byte[] pngHeaderWithDimensions(int width, int height) throws Exception {
        byte[] png = imageBytes("png");
        ByteBuffer buffer = ByteBuffer.wrap(png);
        buffer.putInt(16, width);
        buffer.putInt(20, height);

        CRC32 crc = new CRC32();
        crc.update(png, 12, 17); // "IHDR" plus its 13-byte data payload.
        buffer.putInt(29, (int) crc.getValue());
        return png;
    }
}
