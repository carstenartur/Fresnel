package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VariableLineGratingRasterizerAnnotationTest {

    @Test
    void everyAxisQuantityRendersAnnotationsForBothOrientations() {
        for (LineOrientation orientation : LineOrientation.values()) {
            for (AxisQuantity quantity : AxisQuantity.values()) {
                VariableLineGratingParameters parameters = parameters(orientation, quantity, 5);
                MonochromeRaster raster = assertDoesNotThrow(() ->
                        VariableLineGratingRasterizer.rasterize(parameters, 100, 100));

                assertEquals(118, raster.widthDots());
                assertEquals(118, raster.heightDots());
                assertThat(countBlackOutsideActiveArea(raster, parameters))
                        .as("%s / %s annotation pixels", orientation, quantity)
                        .isGreaterThan(20);
            }
        }
    }

    @Test
    void firstAndLastTickLabelsRemainClippedToThePhysicalPage() {
        for (LineOrientation orientation : LineOrientation.values()) {
            VariableLineGratingParameters parameters = parameters(
                    orientation,
                    AxisQuantity.DEVICE_DOTS_PER_PERIOD,
                    21);
            MonochromeRaster raster = VariableLineGratingRasterizer.rasterize(parameters, 100, 100);

            assertThat(countBlackInEdgeStrip(raster, 12)).isGreaterThan(0);
            assertThat(unusedPaddingBitsAreClear(raster)).isTrue();
        }
    }

    @Test
    void axisLabelsExposePositionAndSelectedQuantityDeterministically() {
        VariableLineGratingParameters pitch = parameters(
                LineOrientation.VERTICAL, AxisQuantity.PITCH_UM, 3);
        assertEquals("2540UM", VariableLineGratingRasterizer.axisLabel(pitch, 0.0, 100));

        VariableLineGratingParameters frequency = parameters(
                LineOrientation.VERTICAL, AxisQuantity.LINES_PER_MM, 3);
        assertEquals("0.4L/MM", VariableLineGratingRasterizer.axisLabel(frequency, 0.5, 100));

        VariableLineGratingParameters dots = parameters(
                LineOrientation.HORIZONTAL, AxisQuantity.DEVICE_DOTS_PER_PERIOD, 3);
        assertEquals("10DOT", VariableLineGratingRasterizer.axisLabel(dots, 1.0, 100));
    }

    private static long countBlackOutsideActiveArea(
            MonochromeRaster raster,
            VariableLineGratingParameters parameters) {
        VariableLineGratingModel.Layout layout = VariableLineGratingModel.layout(parameters);
        double mmPerDotX = Units.INCH_MM / raster.dpiX();
        double mmPerDotY = Units.INCH_MM / raster.dpiY();
        long count = 0;
        for (int y = 0; y < raster.heightDots(); y++) {
            double yMm = (y + 0.5) * mmPerDotY;
            for (int x = 0; x < raster.widthDots(); x++) {
                if (!raster.isBlack(x, y)) continue;
                double xMm = (x + 0.5) * mmPerDotX;
                boolean inside = xMm >= layout.activeXmm()
                        && xMm < layout.activeXmm() + layout.activeWidthMm()
                        && yMm >= layout.activeYmm()
                        && yMm < layout.activeYmm() + layout.activeHeightMm();
                if (!inside) count++;
            }
        }
        return count;
    }

    private static long countBlackInEdgeStrip(MonochromeRaster raster, int stripWidth) {
        long count = 0;
        for (int y = 0; y < raster.heightDots(); y++) {
            for (int x = 0; x < raster.widthDots(); x++) {
                if ((x < stripWidth || x >= raster.widthDots() - stripWidth
                        || y < stripWidth || y >= raster.heightDots() - stripWidth)
                        && raster.isBlack(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean unusedPaddingBitsAreClear(MonochromeRaster raster) {
        int padding = raster.rowBytes() * 8 - raster.widthDots();
        if (padding == 0) return true;
        int mask = (1 << padding) - 1;
        for (int y = 0; y < raster.heightDots(); y++) {
            byte[] row = raster.row(y);
            if ((row[row.length - 1] & mask) != 0) return false;
        }
        return true;
    }

    private static VariableLineGratingParameters parameters(
            LineOrientation orientation,
            AxisQuantity quantity,
            int ticks) {
        return new VariableLineGratingParameters(
                30.0,
                30.0,
                orientation,
                2540.0,
                2540.0,
                GratingProgression.LINEAR_PITCH,
                ProgressionDirection.NORMAL,
                0.5,
                0.0,
                Polarity.POSITIVE,
                1.0,
                8.0,
                true,
                quantity,
                ticks,
                false,
                0.0,
                100.0);
    }
}
