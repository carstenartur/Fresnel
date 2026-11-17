package org.fresnel.measurement.photometry;

/** Physical screen and camera geometry for an upright, unmirrored camera aimed at the object. */
public record DisplayGeometry(double screenWidthMm, double screenHeightMm, int widthPx, int heightPx,
                              double objectDistanceMm, double cameraOffsetXmm, double cameraOffsetYmm,
                              int lightCount, double patchDiameterPercent, int settleDelayMs, double cameraGamma) {
    public DisplayGeometry {
        bounded(screenWidthMm, 100, 2000, "screen width (mm)");
        bounded(screenHeightMm, 60, 1500, "screen height (mm)");
        bounded(widthPx, 64, 4096, "screen width (px)");
        bounded(heightPx, 64, 4096, "screen height (px)");
        if ((long) widthPx * heightPx > 8_847_360L)
            throw new IllegalArgumentException("Screen raster exceeds 8,847,360 pixels");
        bounded(objectDistanceMm, 50, 4000, "object distance (mm)");
        bounded(cameraOffsetXmm, -1000, 1000, "camera offset X (mm)");
        bounded(cameraOffsetYmm, -1000, 1000, "camera offset Y (mm)");
        if (lightCount != 4 && lightCount != 8 && lightCount != 12 && lightCount != 16)
            throw new IllegalArgumentException("Choose 4, 8, 12 or 16 directional lights");
        bounded(patchDiameterPercent, 4, 16, "patch diameter (%)");
        bounded(settleDelayMs, 100, 5000, "settle delay (ms)");
        bounded(cameraGamma, 1, 3, "camera gamma");
        if (DisplayIlluminationSequenceGenerator.conditionNumber(screenWidthMm, screenHeightMm,
                objectDistanceMm, lightCount) > 25)
            throw new IllegalArgumentException("Light directions are too similar. Move the object closer or use a larger screen.");
        // Require a front-facing camera basis for every light.
        if (objectDistanceMm*objectDistanceMm <= 0.4*(Math.abs(cameraOffsetXmm)*screenWidthMm
                + Math.abs(cameraOffsetYmm)*screenHeightMm))
            throw new IllegalArgumentException("Place the camera closer to the screen center or increase object distance.");
    }

    private static void bounded(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("Invalid " + name + " (allowed " + min + " to " + max + ")");
    }
}
