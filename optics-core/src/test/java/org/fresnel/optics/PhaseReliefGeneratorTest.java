package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseReliefGeneratorTest {

    @Test
    void convertsSimplePhaseRampToExpectedHeights() {
        BufferedImage img = new BufferedImage(4, 2, BufferedImage.TYPE_BYTE_GRAY);
        int[] ramp = {0, 85, 170, 255};
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.getRaster().setSample(x, y, 0, ramp[x]);
            }
        }

        ReliefParameters p = new ReliefParameters(500.0, 0.5, 2.0 * Math.PI);
        double[][] h = PhaseReliefGenerator.toHeightMapMm(img, p);

        double hMax = Units.nmToMm(500.0) / 0.5;
        assertEquals(0.0, h[0][0], 1e-12);
        assertEquals(hMax / 3.0, h[0][1], 2e-6);
        assertEquals(2.0 * hMax / 3.0, h[0][2], 2e-6);
        assertEquals(hMax, h[0][3], 2e-6);
    }

    @Test
    void exportsBinaryStlForHeightMap() {
        double[][] h = {
                {0.0, 0.05},
                {0.10, 0.15}
        };
        byte[] stl = StlExporter.toBinaryStl(h, 0.2);

        // 2x2 grid -> 12 triangles in a closed mesh.
        assertEquals(84 + 12 * 50, stl.length);
        int triCount = ByteBuffer.wrap(stl, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertEquals(12, triCount);
    }

    @Test
    void exportsAsciiStlOptionally() {
        double[][] h = {
                {0.0, 0.01},
                {0.02, 0.03}
        };
        String stl = StlExporter.toAsciiStl(h, 0.1, "test_relief");
        assertTrue(stl.startsWith("solid test_relief"));
        assertTrue(stl.contains("facet normal"));
        assertTrue(stl.endsWith("endsolid test_relief\n"));
    }

    @Test
    void rejectsNullReliefParameters() {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY);
        assertThrows(IllegalArgumentException.class, () -> PhaseReliefGenerator.toHeightMapMm(img, null));
    }

    @Test
    void rejectsNonFiniteReliefParameters() {
        assertThrows(IllegalArgumentException.class, () -> new ReliefParameters(Double.NaN, 0.5, 2.0 * Math.PI));
        assertThrows(IllegalArgumentException.class, () -> new ReliefParameters(550.0, Double.POSITIVE_INFINITY, 2.0 * Math.PI));
        assertThrows(IllegalArgumentException.class, () -> new ReliefParameters(550.0, 0.5, Double.NEGATIVE_INFINITY));
    }

    @Test
    void exportsSolidWithBaseThicknessWhenReliefIsFlat() {
        double[][] h = {
                {0.0, 0.0},
                {0.0, 0.0}
        };
        byte[] stl = StlExporter.toBinaryStl(h, 0.2);
        ByteBuffer bb = ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(84);
        float nx = bb.getFloat();
        float ny = bb.getFloat();
        float nz = bb.getFloat();
        float ax = bb.getFloat();
        float ay = bb.getFloat();
        float az = bb.getFloat();
        assertTrue(Float.isFinite(nx));
        assertTrue(Float.isFinite(ny));
        assertTrue(Float.isFinite(nz));
        assertTrue(Float.isFinite(ax));
        assertTrue(Float.isFinite(ay));
        assertTrue(az > 0.0f);
    }
}
