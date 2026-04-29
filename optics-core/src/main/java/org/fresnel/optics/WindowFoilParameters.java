package org.fresnel.optics;

import java.util.List;

/**
 * Parameters for a window-foil layout: a rectangular sheet ({@code sheetWidthMm} ×
 * {@code sheetHeightMm}) tiled with hexagonal macro cells on a flat-top hex grid.
 *
 * <p>Each macro cell uses the same {@code subDiameterMm}, {@code subPitchMm} and
 * {@code wavelengthNm}, but can have its own focal length and target offset (for
 * artistic projection patterns). If only one cell-spec is supplied it is reused
 * for every cell.
 *
 * <p>The cell flat-to-flat distance is {@code √3·macroRadiusMm}; the tiling pitch
 * is exactly that so the cells share edges (lückenlos / gap-less). Cells whose
 * centre lies inside the rectangle are rendered (truncated at the sheet edge).
 *
 * @param sheetWidthMm   sheet width, mm
 * @param sheetHeightMm  sheet height, mm
 * @param macroRadiusMm  circumscribed radius of each hex macro cell, mm
 * @param subDiameterMm  sub-element diameter, mm
 * @param subPitchMm     sub-element pitch, mm
 * @param wavelengthNm   design wavelength, nm
 * @param dpi            printer resolution
 * @param maskType       binary amplitude or greyscale phase
 * @param polarity       mask polarity
 * @param cellSpecs      per-cell specifications (focal length + target offset).
 *                       If empty, an on-axis 1 m focus is used everywhere; if size 1
 *                       the spec is reused for every cell; otherwise iterated cyclically
 *                       in row-major cell order.
 * @param drawCropMarks  if true, draw thin crop marks on the sheet corners and at the
 *                       top of each macro cell to aid alignment after cutting
 */
public record WindowFoilParameters(
        double sheetWidthMm,
        double sheetHeightMm,
        double macroRadiusMm,
        double subDiameterMm,
        double subPitchMm,
        double wavelengthNm,
        double dpi,
        MaskType maskType,
        Polarity polarity,
        List<CellSpec> cellSpecs,
        boolean drawCropMarks
) {

    /** Per-cell focal-length and target-offset specification. */
    public record CellSpec(
            double focalLengthMm,
            double targetOffsetXmm,
            double targetOffsetYmm
    ) {
        public CellSpec {
            if (focalLengthMm <= 0) throw new IllegalArgumentException("focalLengthMm must be > 0");
        }
        public static CellSpec onAxis(double focalLengthMm) {
            return new CellSpec(focalLengthMm, 0.0, 0.0);
        }
    }

    public WindowFoilParameters {
        if (sheetWidthMm <= 0) throw new IllegalArgumentException("sheetWidthMm must be > 0");
        if (sheetHeightMm <= 0) throw new IllegalArgumentException("sheetHeightMm must be > 0");
        if (macroRadiusMm <= 0) throw new IllegalArgumentException("macroRadiusMm must be > 0");
        if (subDiameterMm <= 0) throw new IllegalArgumentException("subDiameterMm must be > 0");
        if (subPitchMm < subDiameterMm)
            throw new IllegalArgumentException("subPitchMm must be ≥ subDiameterMm");
        if (subDiameterMm > 2.0 * macroRadiusMm)
            throw new IllegalArgumentException("subDiameterMm must be ≤ 2·macroRadiusMm");
        if (wavelengthNm <= 0) throw new IllegalArgumentException("wavelengthNm must be > 0");
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
        if (maskType == null) throw new IllegalArgumentException("maskType must not be null");
        if (polarity == null) throw new IllegalArgumentException("polarity must not be null");
        cellSpecs = cellSpecs == null ? List.of() : List.copyOf(cellSpecs);
    }

    /** Default cell-spec to use when none supplied: on-axis 1 m focus. */
    public CellSpec defaultCellSpec() { return CellSpec.onAxis(1000.0); }

    /** Spec for the {@code i}-th cell (0-based, row-major) — supports empty/single/list. */
    public CellSpec specForCell(int i) {
        if (cellSpecs.isEmpty()) return defaultCellSpec();
        return cellSpecs.get(i % cellSpecs.size());
    }
}
