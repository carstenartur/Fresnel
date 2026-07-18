# Plugin: Multi-Focus Zone Plate (`multi-focus`)

The **multi-focus** plugin divides the aperture of a zone plate among multiple
independent focal targets. Each pixel is assigned to exactly one target via a
deterministic hash; the zone-plate phase for that pixel is then computed toward
its target.

This produces **N distinct focal spots** simultaneously from a single printed
element. Common use cases:

- Two-point stereo illumination
- Scanning line projectors (line focus)
- Artistic multi-point illumination patterns

## Parameters

| Parameter | Unit | Description |
|-----------|------|-------------|
| `apertureDiameterMm` | mm | Aperture diameter of the combined element |
| `focusPoints` | list | List of `(x, y, z)` focus targets in mm; `z` is the focal distance |
| `wavelengthNm` | nm | Design wavelength |
| `dpi` | dots/inch | Printer resolution |
| `maskType` | — | `BINARY_AMPLITUDE` or `GREYSCALE_PHASE` |
| `polarity` | — | `POSITIVE` or `NEGATIVE` |

### Focus points

Each focus point is a record `FocusPoint(double xMm, double yMm, double zMm)` where
`(xMm, yMm)` is the transverse offset from the optical axis and `zMm` is the
axial focal distance (must be > 0).

For a line-focus design the helper `MultiFocusParameters.lineOfPoints(...)` creates
N equally-spaced points between two endpoints.

## Example images

### Two discrete foci

![Multi-focus — two foci](../assets/plugins/multi-focus/two-foci.png)

[Download the source job](../jobs/multi-focus/two-foci.fresnel)

<!-- fresnel-example:multi-focus/two-foci:start -->
| Parameter | Value |
|---|---:|
| Aperture diameter | 10 mm |
| Focus points | 1. X = -3 mm, Y = 0 mm, Z = 300 mm; 2. X = 3 mm, Y = 0 mm, Z = 300 mm |
| Wavelength | 550 nm |
| Printer DPI | 1200 dpi |
| Mask type | Binary amplitude |
| Polarity | Positive |
<!-- fresnel-example:multi-focus/two-foci:end -->

The aperture is divided into two interleaved sub-apertures. Each half focuses to
one of the two off-axis targets.

### Line focus

![Multi-focus — line focus](../assets/plugins/multi-focus/line-focus.png)

[Download the source job](../jobs/multi-focus/line-focus.fresnel)

<!-- fresnel-example:multi-focus/line-focus:start -->
| Parameter | Value |
|---|---:|
| Aperture diameter | 10 mm |
| Focus points | 1. X = -4 mm, Y = 0 mm, Z = 400 mm; 2. X = -2 mm, Y = 0 mm, Z = 400 mm; 3. X = 0 mm, Y = 0 mm, Z = 400 mm; 4. X = 2 mm, Y = 0 mm, Z = 400 mm; 5. X = 4 mm, Y = 0 mm, Z = 400 mm |
| Wavelength | 550 nm |
| Printer DPI | 1200 dpi |
| Mask type | Binary amplitude |
| Polarity | Positive |
<!-- fresnel-example:multi-focus/line-focus:end -->

Five focus points distributed along a line produce an element that illuminates a
short horizontal line segment. Opening the job preserves the exact five-point
array in the trusted schema widget.

## Java API

```java
// Two discrete foci matching the documentation job
MultiFocusParameters p = new MultiFocusParameters(
        10.0,  // aperture diameter, mm
        List.of(
                new MultiFocusParameters.FocusPoint(-3.0, 0.0, 300.0),
                new MultiFocusParameters.FocusPoint(+3.0, 0.0, 300.0)),
        550.0, 1200.0,
        MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);

RenderResult result = MultiFocusRenderer.render(p);

// Five-point line focus matching the second documentation job
List<MultiFocusParameters.FocusPoint> line =
        MultiFocusParameters.lineOfPoints(
                -4, 0, 400,   // start (x, y, z) mm
                +4, 0, 400,   // end   (x, y, z) mm
                5             // number of points
        );
MultiFocusParameters lineFocusParams = new MultiFocusParameters(
        10.0, line, 550.0, 1200.0,
        MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
```

## Reproducing and verifying the examples

```bash
bash packaging/docs-jobs.sh render \
  docs/jobs/multi-focus/two-foci.fresnel \
  docs/assets/plugins/multi-focus

bash packaging/docs-jobs.sh render \
  docs/jobs/multi-focus/line-focus.fresnel \
  docs/assets/plugins/multi-focus

bash packaging/docs-jobs.sh verify-manifest \
  docs/jobs docs/assets/plugins docs/generated/example-manifest.json
```
