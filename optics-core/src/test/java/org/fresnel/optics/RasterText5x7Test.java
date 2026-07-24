package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertThat(target.points).isNotEmpty();
        assertThat(target.minX()).isEqualTo(10);
        assertThat(target.maxX()).isEqualTo(14);
        assertThat(target.minY()).isEqualTo(20);
        assertThat(target.maxY()).isEqualTo(26);
        assertThat(target.points).contains(
                new Point(11, 20),
                new Point(12, 20),
                new Point(13, 20),
                new Point(10, 23),
                new Point(14, 23));
    }

    @Test
    void scaleExpandsEverySourcePixelWithoutChangingTheShape() {
        CollectingTarget target = new CollectingTarget();
        RasterText5x7.draw(target, 0, 0, "A", 2);

        assertThat(target.minX()).isZero();
        assertThat(target.maxX()).isEqualTo(9);
        assertThat(target.minY()).isZero();
        assertThat(target.maxY()).isEqualTo(13);
        assertThat(target.points).contains(
                new Point(2, 0), new Point(3, 0),
                new Point(2, 1), new Point(3, 1));
    }

    @Test
    void clockwiseRenderingRotatesTheGlyphIntoSevenByFiveBounds() {
        CollectingTarget target = new CollectingTarget();
        RasterText5x7.drawClockwise(target, 30, 40, "L", 1);

        assertThat(target.points).isNotEmpty();
        assertThat(target.minX()).isEqualTo(30);
        assertThat(target.maxX()).isEqualTo(36);
        assertThat(target.minY()).isEqualTo(40);
        assertThat(target.maxY()).isEqualTo(44);
    }

    @Test
    void everyDocumentedGlyphProducesPixelsAndUnknownCharactersRemainBlank() {
        for (char glyph : SUPPORTED.toCharArray()) {
            CollectingTarget target = new CollectingTarget();
            RasterText5x7.draw(target, 0, 0, String.valueOf(glyph), 1);
            assertThat(target.points)
                    .as("glyph %s", glyph)
                    .isNotEmpty();
        }

        CollectingTarget unknown = new CollectingTarget();
        RasterText5x7.draw(unknown, 0, 0, " ?_", 1);
        assertThat(unknown.points).isEmpty();
    }

    @Test
    void aClippingTargetKeepsPageEdgeAnnotationsInsideTheRaster() {
        ClippingTarget target = new ClippingTarget(8, 8);

        assertDoesNotThrow(() -> RasterText5x7.draw(target, -3, -2, "AB", 1));
        assertDoesNotThrow(() -> RasterText5x7.drawClockwise(target, 5, -5, "A", 1));

        assertThat(target.points).isNotEmpty();
        assertThat(target.points).allSatisfy(point -> {
            assertThat(point.x).isBetween(0, 7);
            assertThat(point.y).isBetween(0, 7);
        });
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
