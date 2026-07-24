package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RasterText5x7Test {

    private static final String SUPPORTED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-./:%+";

    @Test
    void widthIsDeterministicForEmptyTextAndScale() {
        assertEquals(0, RasterText5x7.textWidth("", 1));
        assertEquals(5, RasterText5x7.textWidth("A", 1));
        assertEquals(11, RasterText5x7.textWidth("AB", 1));
        assertEquals(22, RasterText5x7.textWidth("AB", 2));
    }

    @Test
    void normalGlyphUsesTheExpectedFiveBySevenBounds() {
        CollectingTarget target = new CollectingTarget();
        RasterText5x7.draw(target, 10, 20, "A", 1);

        assertFalse(target.points.isEmpty());
        assertEquals(10, target.minX());
        assertEquals(14, target.maxX());
        assertEquals(20, target.minY());
        assertEquals(26, target.maxY());
        assertTrue(target.points.contains(new Point(11, 20)));
        assertTrue(target.points.contains(new Point(12, 20)));
        assertTrue(target.points.contains(new Point(13, 20)));
        assertTrue(target.points.contains(new Point(10, 23)));
        assertTrue(target.points.contains(new Point(14, 23)));
    }

    @Test
    void scaleExpandsEverySourcePixelWithoutChangingTheShape() {
        CollectingTarget target = new CollectingTarget();
        RasterText5x7.draw(target, 0, 0, "A", 2);

        assertEquals(0, target.minX());
        assertEquals(9, target.maxX());
        assertEquals(0, target.minY());
        assertEquals(13, target.maxY());
        assertTrue(target.points.contains(new Point(2, 0)));
        assertTrue(target.points.contains(new Point(3, 0)));
        assertTrue(target.points.contains(new Point(2, 1)));
        assertTrue(target.points.contains(new Point(3, 1)));
    }

    @Test
    void clockwiseRenderingRotatesTheGlyphIntoSevenByFiveBounds() {
        CollectingTarget target = new CollectingTarget();
        RasterText5x7.drawClockwise(target, 30, 40, "L", 1);

        assertFalse(target.points.isEmpty());
        assertEquals(30, target.minX());
        assertEquals(36, target.maxX());
        assertEquals(40, target.minY());
        assertEquals(44, target.maxY());
    }

    @Test
    void everyDocumentedGlyphProducesPixelsAndUnknownCharactersRemainBlank() {
        for (char glyph : SUPPORTED.toCharArray()) {
            CollectingTarget target = new CollectingTarget();
            RasterText5x7.draw(target, 0, 0, String.valueOf(glyph), 1);
            assertFalse(target.points.isEmpty(), "glyph " + glyph + " must render");
        }

        CollectingTarget unknown = new CollectingTarget();
        RasterText5x7.draw(unknown, 0, 0, " ?_", 1);
        assertTrue(unknown.points.isEmpty());
    }

    @Test
    void aClippingTargetKeepsPageEdgeAnnotationsInsideTheRaster() {
        ClippingTarget target = new ClippingTarget(8, 8);

        assertDoesNotThrow(() -> RasterText5x7.draw(target, -3, -2, "AB", 1));
        assertDoesNotThrow(() -> RasterText5x7.drawClockwise(target, 5, -5, "A", 1));

        assertFalse(target.points.isEmpty());
        for (Point point : target.points) {
            assertTrue(point.x >= 0 && point.x <= 7, "x outside clipped target: " + point);
            assertTrue(point.y >= 0 && point.y <= 7, "y outside clipped target: " + point);
        }
    }

    private static class CollectingTarget implements RasterText5x7.Target {
        final Set<Point> points = new HashSet<>();

        @Override
        public void setBlack(int x, int y) {
            points.add(new Point(x, y));
        }

        int minX() { return points.stream().mapToInt(point -> point.x).min().orElseThrow(); }
        int maxX() { return points.stream().mapToInt(point -> point.x).max().orElseThrow(); }
        int minY() { return points.stream().mapToInt(point -> point.y).min().orElseThrow(); }
        int maxY() { return points.stream().mapToInt(point -> point.y).max().orElseThrow(); }
    }

    private static final class ClippingTarget extends CollectingTarget {
        private final int width;
        private final int height;

        private ClippingTarget(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public void setBlack(int x, int y) {
            if (x >= 0 && x < width && y >= 0 && y < height) {
                super.setBlack(x, y);
            }
        }
    }
}
