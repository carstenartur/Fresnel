package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundOrientedSchlierenControllerTest {

    private final BackgroundOrientedSchlierenController controller =
            new BackgroundOrientedSchlierenController();

    @Test
    void exportReturnsFullResolutionDeterministicPngWithProvenanceHeaders()
            throws Exception {
        BackgroundOrientedSchlierenRequest request = request(123L);

        ResponseEntity<byte[]> first = controller.export(request);
        ResponseEntity<byte[]> second = controller.export(request);

        assertEquals(MediaType.IMAGE_PNG, first.getHeaders().getContentType());
        assertEquals("123", first.getHeaders().getFirst("X-Fresnel-Pattern-Seed"));
        assertNotNull(first.getHeaders().getFirst("X-Fresnel-Dot-Count"));
        assertNotNull(first.getHeaders().getFirst("X-Fresnel-Achieved-Fill-Ratio"));
        assertArrayEquals(first.getBody(), second.getBody());

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(first.getBody()));
        assertEquals(640, image.getWidth());
        assertEquals(480, image.getHeight());
    }

    @Test
    void previewKeepsAspectRatioAndBoundsLongestSide() throws Exception {
        ResponseEntity<byte[]> response = controller.preview(new BackgroundOrientedSchlierenRequest(
                2000, 1000, 300.0, 8L, 7, 0.05, 3, 64, true, false));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        assertEquals(1600, image.getWidth());
        assertEquals(800, image.getHeight());
        assertEquals("inline", response.getHeaders().getContentDisposition().getType());
    }

    @Test
    void aDifferentSeedChangesThePng() throws Exception {
        byte[] first = controller.export(request(1L)).getBody();
        byte[] second = controller.export(request(2L)).getBody();

        assertTrue(!Arrays.equals(first, second));
    }

    @Test
    void crossFieldSafetyChecksRemainAuthoritativeBehindBeanValidation() {
        BackgroundOrientedSchlierenRequest request =
                new BackgroundOrientedSchlierenRequest(
                        4096, 4096, 300.0, 1L, 7, 0.12, 3, 64, true, false);

        assertThrows(IllegalArgumentException.class, request::toParameters);
    }

    private static BackgroundOrientedSchlierenRequest request(long seed) {
        return new BackgroundOrientedSchlierenRequest(
                640, 480, 300.0, seed, 7, 0.05, 3, 48, true, false);
    }
}
