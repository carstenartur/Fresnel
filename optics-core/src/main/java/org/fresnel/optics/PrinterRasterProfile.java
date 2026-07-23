package org.fresnel.optics;

import java.util.Set;

/** Trusted mapping from physical page coordinates to native printer raster coordinates. */
public record PrinterRasterProfile(
        String id,
        int version,
        PrinterLanguageDialect dialect,
        int dpiX,
        int dpiY,
        int pageWidthDots,
        int pageHeightDots,
        int printableOriginXDots,
        int printableOriginYDots,
        int printableWidthDots,
        int printableHeightDots,
        String mediaSize,
        PclPageOrientation pageOrientation,
        DeviceAxis pageXAxisMapsTo,
        DeviceAxis pageYAxisMapsTo,
        Set<PclCompression> compressionModes
) {
    public PrinterRasterProfile {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("profile id must not be blank");
        if (version <= 0) throw new IllegalArgumentException("profile version must be positive");
        if (dialect == null) throw new IllegalArgumentException("dialect must not be null");
        if (dpiX <= 0 || dpiY <= 0) throw new IllegalArgumentException("device DPI must be positive");
        if (pageWidthDots <= 0 || pageHeightDots <= 0
                || printableWidthDots <= 0 || printableHeightDots <= 0) {
            throw new IllegalArgumentException("page and printable dimensions must be positive");
        }
        if (printableOriginXDots < 0 || printableOriginYDots < 0
                || printableOriginXDots + printableWidthDots > pageWidthDots
                || printableOriginYDots + printableHeightDots > pageHeightDots) {
            throw new IllegalArgumentException("printable bounds must lie inside the page");
        }
        if (mediaSize == null || mediaSize.isBlank()) {
            throw new IllegalArgumentException("mediaSize must not be blank");
        }
        if (pageOrientation == null || pageXAxisMapsTo == null || pageYAxisMapsTo == null) {
            throw new IllegalArgumentException("page orientation and axis mapping must not be null");
        }
        if (pageXAxisMapsTo == pageYAxisMapsTo) {
            throw new IllegalArgumentException("page X and Y must map to different device axes");
        }
        compressionModes = compressionModes == null ? Set.of() : Set.copyOf(compressionModes);
        if (compressionModes.isEmpty()) {
            throw new IllegalArgumentException("at least one compression mode is required");
        }
    }

    public DeviceAxis testedDeviceAxis(LineOrientation orientation) {
        return orientation == LineOrientation.VERTICAL ? pageXAxisMapsTo : pageYAxisMapsTo;
    }

    public int dpiForDeviceAxis(DeviceAxis axis) {
        return axis == DeviceAxis.X ? dpiX : dpiY;
    }

    public int dpiForPageX() {
        return dpiForDeviceAxis(pageXAxisMapsTo);
    }

    public int dpiForPageY() {
        return dpiForDeviceAxis(pageYAxisMapsTo);
    }

    public int dpiForTestedAxis(LineOrientation orientation) {
        return dpiForDeviceAxis(testedDeviceAxis(orientation));
    }
}
