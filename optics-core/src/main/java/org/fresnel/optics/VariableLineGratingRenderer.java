package org.fresnel.optics;

/** Binary raster renderer for previews and ordinary PNG/PDF export. */
public final class VariableLineGratingRenderer {

    private VariableLineGratingRenderer() {}

    public static RenderResult render(VariableLineGratingParameters p) {
        MonochromeRaster raster = VariableLineGratingRasterizer.rasterize(p, p.dpi(), p.dpi());
        return new RenderResult(raster.toBufferedImage(), Units.pixelSizeMm(p.dpi()));
    }
}
