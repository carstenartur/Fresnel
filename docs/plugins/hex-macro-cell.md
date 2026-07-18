# Plugin: Hex Macro Cell (`hex-macro-cell`)

The **hex macro cell** plugin renders a hexagonal aperture tiled with many small
sub-zone-plates on a triangular lattice. All sub-elements share a common image
plane so they constructively project the same focal spot — but each sub-element
has its own off-axis angle because it sits at a different position within the
macro hex.

This design is used for **projection optics** where a larger aperture is needed
than a single small zone plate would allow, while keeping the outer-zone width
(and therefore the required printer resolution) manageable.

## Parameters

| Parameter | Unit | Description |
|-----------|------|-------------|
| `macroRadiusMm` | mm | Circumscribed radius (centre → vertex) of the outer hexagon |
| `subDiameterMm` | mm | Diameter of each sub-zone-plate |
| `subPitchMm` | mm | Centre-to-centre spacing of sub-elements on the hex lattice (≥ `subDiameterMm`) |
| `focalLengthMm` | mm | z-distance from macro plane to common image plane |
| `targetOffsetXmm` | mm | X offset of the common focal target from the macro-cell centre |
| `targetOffsetYmm` | mm | Y offset of the common focal target from the macro-cell centre |
| `wavelengthNm` | nm | Design wavelength |
| `dpi` | dots/inch | Printer resolution |
| `maskType` | — | `BINARY_AMPLITUDE` or `GREYSCALE_PHASE` |
| `polarity` | — | `POSITIVE` or `NEGATIVE` |

## Example image

### On-axis hex macro cell

![Hex macro cell — on-axis](../assets/plugins/hex-macro-cell/on-axis.png)

[Download the source job](../jobs/hex-macro-cell/on-axis.fresnel)

<!-- fresnel-example:hex-macro-cell/on-axis:start -->
| Parameter | Value |
|---|---:|
| Macro radius | 15 mm |
| Sub-element diameter | 5 mm |
| Sub-element pitch | 5.5 mm |
| Focal length | 500 mm |
| Target offset X | 0 mm |
| Target offset Y | 0 mm |
| Wavelength | 550 nm |
| Printer DPI | 400 dpi |
| Mask type | Binary amplitude |
| Polarity | Positive |
<!-- fresnel-example:hex-macro-cell/on-axis:end -->

The hexagonal outline is clearly visible; each sub-zone-plate inside focuses
toward the same on-axis point 500 mm away. Open the downloaded job in Fresnel to
inspect these exact normalized parameters in the schema-driven editor.

## Java API

```java
// On-axis convenience constructor matching the documentation job
HexMacroCellParameters p = HexMacroCellParameters.onAxis(
        15.0,  // macro radius, mm
        5.0,   // sub-element diameter, mm
        5.5,   // sub-element pitch, mm
        500.0, // focal length, mm
        550.0, // wavelength, nm
        400.0  // DPI
);
RenderResult result = HexMacroCellRenderer.render(p);

// Count sub-elements before rendering
int n = HexMacroCellRenderer.countSubElements(p);

// Get hex lattice centres (useful for custom rendering)
List<double[]> centres =
        HexMacroCellRenderer.hexLatticeCentresInsideHex(15.0, 5.5);
```

## Reproducing and verifying the example

```bash
# Render only this public production job.
bash packaging/docs-jobs.sh render \
  docs/jobs/hex-macro-cell/on-axis.fresnel \
  docs/assets/plugins/hex-macro-cell

# Verify every migrated job, asset and the checked-in manifest.
bash packaging/docs-jobs.sh verify-manifest \
  docs/jobs docs/assets/plugins docs/generated/example-manifest.json
```
