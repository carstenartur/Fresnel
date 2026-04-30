package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class RgbZonePlateRendererTest {

    @Test
    void rendersRgbImageOfSameSizeAsMonoChannel() {
        SingleZonePlateParameters base = SingleZonePlateParameters.onAxis(5.0, 50.0, 550.0, 600.0);
        RenderResult mono = ZonePlateRenderer.render(base);
        RenderResult rgb = RgbZonePlateRenderer.render(base, 630.0, 532.0, 450.0);
        assertEquals(mono.image().getWidth(), rgb.image().getWidth());
        assertEquals(mono.image().getHeight(), rgb.image().getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, rgb.image().getType());
    }

    @Test
    void channelsAreIndependent() {
        // The blue and red channels at very different wavelengths should differ in many pixels.
        SingleZonePlateParameters base = SingleZonePlateParameters.onAxis(5.0, 50.0, 550.0, 600.0);
        RenderResult rgb = RgbZonePlateRenderer.render(base, 700.0, 550.0, 400.0);
        BufferedImage img = rgb.image();
        int diff = 0;
        int total = 0;
        int cx = img.getWidth() / 2;
        for (int y = cx - 30; y <= cx + 30; y++) {
            for (int x = cx - 30; x <= cx + 30; x++) {
                int rgbInt = img.getRGB(x, y);
                int red = (rgbInt >> 16) & 0xFF;
                int blue = rgbInt & 0xFF;
                if (red != blue) diff++;
                total++;
            }
        }
        assertTrue(diff > total / 10,
                "expected substantial channel divergence, only " + diff + "/" + total);
    }

    @Test
    void rejectsInvalidWavelengths() {
        SingleZonePlateParameters base = SingleZonePlateParameters.onAxis(5.0, 50.0, 550.0, 600.0);
        assertThrows(IllegalArgumentException.class,
                () -> RgbZonePlateRenderer.render(base, -1.0, 550.0, 450.0));
    }
}
