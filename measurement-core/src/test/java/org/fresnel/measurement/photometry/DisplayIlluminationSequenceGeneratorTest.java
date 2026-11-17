package org.fresnel.measurement.photometry;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DisplayIlluminationSequenceGeneratorTest {
    private final DisplayGeometry geometry = new DisplayGeometry(500, 300, 120, 80, 450, 0, 150, 8, 12, 200, 2.2);

    @Test void deterministicRasterIdentityAndPhysicalGeometry() throws Exception {
        var manifest = DisplayIlluminationSequenceGenerator.generate(geometry);
        assertEquals(manifest, DisplayIlluminationSequenceGenerator.generate(geometry));
        assertEquals(10, manifest.patterns().size());
        assertEquals("dark", manifest.patterns().getFirst().id());
        assertEquals("flat", manifest.patterns().get(1).id());
        for (var p : manifest.patterns()) {
            var image = ImageIO.read(new ByteArrayInputStream(DisplayIlluminationSequenceGenerator.png(geometry, p.id())));
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("FRESNEL-PATTERN-RGB8-V1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(java.nio.ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight()).array());
            int brightPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int gray = image.getRGB(x, y) & 255;
                digest.update(new byte[] {(byte) gray, (byte) gray, (byte) gray});
                if (gray > 0) brightPixels++;
            }
            assertEquals(p.pixelSha256(), HexFormat.of().formatHex(digest.digest()));
            if (p.id().equals("dark")) assertEquals(0, brightPixels);
            else if (p.id().equals("flat")) assertEquals(9600, brightPixels);
            else {
                assertTrue(brightPixels > 10 && brightPixels < 100);
                var l = p.direction();
                assertEquals(1, Math.sqrt(l.x()*l.x() + l.y()*l.y() + l.z()*l.z()), 1e-10);
                assertTrue(p.relativeIntensity() > 0 && p.relativeIntensity() <= 1);
            }
        }
        assertTrue(manifest.patterns().get(2).direction().x() > 0);
        assertTrue(manifest.patterns().get(4).direction().y() > manifest.patterns().get(8).direction().y());
    }

    @Test void rejectsIllConditionedGeometryAndBadInputsBeforeCapture() {
        assertThrows(IllegalArgumentException.class, () -> new DisplayGeometry(100,100,128,128,4000,0,0,8,12,200,2.2));
        assertThrows(IllegalArgumentException.class, () -> new DisplayGeometry(500,300,99999,800,450,0,0,8,12,200,2.2));
        assertThrows(IllegalArgumentException.class, () -> new DisplayGeometry(500,300,128,128,450,0,0,5,12,200,2.2));
        assertThrows(IllegalArgumentException.class, () -> DisplayIlluminationSequenceGenerator.png(geometry, "light-99"));
    }
}
