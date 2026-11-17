# Background-Oriented Schlieren

- **Plugin ID:** `background-oriented-schlieren`
- **Kind:** `MEASUREMENT`
- **Stability:** `EXPERIMENTAL`
- **Plugin algorithm:** `background-oriented-schlieren/1`
- **Target generator algorithm:** `background-oriented-schlieren-target/1`

Background-Oriented Schlieren (BOS) makes small apparent movements of a
high-contrast background visible. In a typical household experiment, a camera
first records a random-dot target without the disturbance and then records the
same target with warm or moving air between camera and target.

Fresnel generates a reproducible dot target for a screen or a physical-size printout
and measures a pair of still images in the BOS editor. Use the same stationary target and camera settings for both
exposures. Ordinary PNG or JPEG uploads are sufficient.

## Check the reference photograph

Choose **Reference image** to see the photograph and its measured quality before
selecting a disturbed frame. The check uses orientation-corrected BT.709 luma
at up to 1,024 pixels on the longest side. It reports the 1st–99th percentile
contrast, fractions near black (0–3) and white (252–255), median
area-equivalent diameter of isolated thresholded features, and an edge-width
estimate from contrast divided by the median luminance step at crossings.
Edge width is a focus or motion-blur indicator. A second image of matching
dimensions enables comparison of the two edge widths.

The occupancy estimate counts 15 × 15 analysis windows that contain at least
two detected features. The assistant suggests changes to framing, lighting,
focus or the dot field when the measurements warrant them. These metrics are
sampling-dependent, especially for unresolved dots. The resulting experiment
records the sampled dimensions and foreground statistics.

With a generated target selected, the assistant searches for its four distinct
corner shapes. It checks their polarity, shape agreement, relative size,
orientation and quadrilateral geometry, then projects the manifest's active
field into the reference image. The yellow outline shows that field and the
blue rectangle shows the area used for analysis. Missing or indistinct markers
leave the full frame selected. Use **Adjust analysis region** to specify a
rectangle as percentages of the oriented image. Both images use the same crop
before displacement analysis, and the result records the source-pixel offset
and scale. The source files retain their original pixels.

## Analyze a still pair

1. Keep the outer dot field undisturbed in both exposures. Select **Reference
   image** and **Disturbed image**. Select the camera motion model, then
   **Analyze still pair**. Translation is the default. Affine also fits small
   rotation, scale and shear. Perspective fits a planar target viewed from a
   slightly different angle.
2. Check the accepted windows and registration residual. Inspect the stabilized
   disturbed frame alongside the map. Switch between magnitude, signed horizontal
   or vertical displacement and normalized correlation. Display amplification
   changes the colors of the stored grid.
3. Download the PNG visualization or numeric JSON. Use **Saved experiments** to
   keep the source images and result together in an owner-scoped session and
   export a checksummed experiment bundle.

The worker checks each image header before decoding. It accepts PNG and JPEG up
to 20 MiB, 24 megapixels and 16,384 pixels per side, normalizes orientation, and
requires equal source dimensions. It composites transparency on white and
uses BT.709 luma coefficients on browser-decoded sRGB. The analysis raster
preserves aspect ratio, has at most 512 pixels on its longest side and at
least 96 on its shortest. The original files remain available at full size.

For each pair, 12 outer patches measure global camera motion by zero-mean
normalized cross-correlation (ZNCC), using 15 × 15 interrogation windows and
an integer search of ±9 analysis pixels. A fit requires at least nine agreeing
anchors within 0.85 pixels, with support in all four quadrants. Affine and
projective modes test nine-anchor subsets, refit the consensus and independently
predict each anchor from the others (maximum held-out error 1.35 px). Corner
motion is limited to 18 px. The exported row-major 3 × 3 matrix maps reference
pixels to disturbed pixels within the selected region. The disturbed luminance is
bilinearly sampled onto the reference grid before local correlation. Windows
that would sample beyond the frame are masked. Translation uses the original
integer search center and subtracts the fitted fractional shift. The local
search radius is ±5 px with parabolic peak interpolation. Flat windows,
boundary peaks, correlations below 0.65, competing peaks within 0.055 and
isolated outliers are masked. At least 45% of the grid must survive.
Missing vectors are null and excluded from the robust 95th-percentile color
limit (minimum 0.15 px). The JSON records coordinates in analysis pixels
(origin top left, +x right, +y down), full-resolution conversion factors,
source SHA-256, settings and registration statistics. The configured
target identity is recorded when a generated target is selected.

The numeric fixtures exercise fractional camera motion, a smooth local field
under camera translation, occlusion, illumination changes, repeated patterns
and excessive motion. For a textured 160 × 160 fixture the translation error
must be below 0.22 analysis pixels and the local field error below 0.4 pixels.
On fixtures with camera rotation, shear or perspective and optional local
displacement, the camera prediction error at nine independent locations must
stay below 0.55 px. At least 88% of the grid must survive, with a 95th-percentile
vector error below 0.40 px and central local-field error below 0.48 px.

## What is available now

The trusted built-in plugin provides:

- a bounded, deterministic random-dot generator in `measurement-core`;
- a fixed, versioned pseudo-random sequence independent of JDK random classes;
- a quiet border outside the generated dot field;
- four asymmetric orientation fiducials outside that active region;
- lossless grayscale PNG output with physical DPI metadata;
- a data-only manifest with target geometry, seed, dot statistics and semantic
  SHA-256;
- schema-driven editing and portable `.fresnel` jobs;
- a public bounded preview and manifest endpoint;
- authenticated PNG, vector SVG and physical-size PDF downloads.

The parameter schema cannot name code, commands, local paths or remote URLs.
The implementation is selected only through the reviewed `PluginRegistry`.

## Generate a target in the application

1. Open **BOS target** in the Fresnel navigation.
2. Choose the source raster size. A screen target is sharpest when one source
   pixel maps to one display pixel.
3. Adjust dot diameter, requested fill ratio and minimum spacing only when the
   default pattern is unsuitable for the camera distance or resolution.
4. Select **Generate target preview**.
5. Check the target ID, hash, active region and physical dimensions shown below
   the controls.
6. Download the PNG and its manifest. Save the `.fresnel` job when the target
   must be reproduced later.

The [1920 × 1080 example job](../../examples/background-oriented-schlieren/screen-target.fresnel)
opens in the same editor and includes a production plan for PNG, SVG and A4 PDF outputs.

Changing the seed changes dot placement. Keeping every parameter unchanged
reproduces the same pixels, semantic hash and target ID.

## Screen target

Use a lossless viewer or Fresnel's generated PNG. Disable interpolation where
the viewer permits it. The **Show target full screen** action retains the
image's natural raster size and provides scrolling rather than silently fitting
large targets to the viewport; the browser's normal Escape key exits
presentation mode.

Browser zoom, operating-system scaling and device pixel ratio can still prevent
one CSS pixel from corresponding to one physical display pixel, so record the
actual presentation setup in the experiment notes.

The target must remain stationary and unchanged between the reference and
measurement captures. Do not place browser controls, labels or a pointer over
the active dot region.

## Printed target

Choose **Download print SVG** for a vector drawing or **Download print PDF** for a
printable page. Both derive each pixel's physical width from `25.4 / intendedDpi`
in millimetres. The target ID is printed in the top quiet border, clear of the
orientation markers and the active dot field. The PNG also records its intended
resolution in a `pHYs` chunk.

**Exact target sheet** sizes the PDF page to its contents. Select an A-series
paper size for a 5 mm printing margin. Larger targets tile across pages with
5 mm overlaps. Print at **Actual size / 100%** and turn off driver scaling.
Measure the distance between the outer ticks of the optional **50 mm
calibration strip**. The strip adds 12 mm below the target. For very narrow
targets, the sheet widens to 56 mm to accommodate the ruler. The active field
retains its original dimensions in either case.

New jobs saved from this editor include PNG, SVG and PDF production outputs.
For SVG and PDF, `options.calibrationStrip` is a boolean and defaults to
`true`. PDF accepts the normal `sheet` values and uses `printScale: 1`.
Non-unit scale factors fail to protect the target calibration.

## Parameters and hard limits

| Parameter | Meaning | Version-1 bound |
|---|---|---:|
| `widthPx` | source raster width | 320–6000 px |
| `heightPx` | source raster height | 240–6000 px |
| `intendedDpi` | physical print resolution | 50–2400 dpi |
| `patternSeed` | deterministic dot-layout seed | JavaScript-safe non-negative integer |
| `dotDiameterPx` | circular feature diameter | 2–32 px |
| `targetFillRatio` | requested active-region foreground area | 0.02–0.25 |
| `minimumDotSpacingPx` | clear edge-to-edge spacing after jitter | 1–32 px |
| `borderPx` | quiet border and fiducial area | 24–512 px |
| `fiducialsEnabled` | draw four asymmetric markers | boolean |
| `invertPattern` | bright-on-dark instead of dark-on-bright | boolean |

In addition to the individual bounds, width × height may not exceed 24,000,000
pixels. Cross-field validation also rejects a border that consumes the active
region, insufficient fiducial space, or an impossible fill ratio for the
selected dot diameter and spacing.

## Manifest and reproducibility

A `.fresnel` job records the plugin compatibility version
`background-oriented-schlieren/1`. The generated target manifest separately
records `background-oriented-schlieren-target/1`, allowing the target algorithm
to evolve independently from later capture and analysis stages.

The manifest records:

- target-generator algorithm version and stable target ID;
- semantic SHA-256 over parameters, geometry and exact grayscale pixels;
- source dimensions, intended DPI and physical size;
- seed, requested and actual fill ratio, dot count and cell pitch;
- active-region rectangle;
- fiducial identities, shapes and rectangles;
- inversion and border settings.

The PNG response repeats the target ID and hash in headers and uses the semantic
hash as its ETag. The browser compares those headers with the independently
loaded manifest before displaying the preview. The hash describes the target
semantics and pixels, not a particular HTTP transfer.

## HTTP API

Public bounded operations:

```text
POST /api/measurements/background-oriented-schlieren/target/manifest
POST /api/measurements/background-oriented-schlieren/target/preview.png
```

Add `?thumbnail=true` to the preview URL for a nearest-neighbor PNG with a
maximum side of 1600 pixels. The identity headers describe the full source
target. Native-size preview and production PNGs carry the intended print DPI.

Authenticated production download:

```text
POST /api/measurements/background-oriented-schlieren/target/export.png
POST /api/measurements/background-oriented-schlieren/target/export.svg
POST /api/measurements/background-oriented-schlieren/target/export.pdf
```

The two print endpoints accept `?calibrationStrip=false` to omit the ruler.
The PDF endpoint also accepts `sheet=FIT` (default) or an A-series sheet size
from A4 through A0.

All operations accept the same JSON parameter object. The PNG endpoints return:

```text
ETag
X-Fresnel-Target-SHA256
X-Fresnel-Target-Id
X-Fresnel-Active-Region
X-Fresnel-Pattern-Seed
X-Fresnel-Dot-Count
X-Fresnel-Achieved-Fill-Ratio
X-Content-Type-Options: nosniff
```

Example manifest request:

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -d '{
    "widthPx": 1600,
    "heightPx": 1000,
    "intendedDpi": 150,
    "patternSeed": 20260823,
    "dotDiameterPx": 7,
    "targetFillRatio": 0.12,
    "minimumDotSpacingPx": 3,
    "borderPx": 64,
    "fiducialsEnabled": true,
    "invertPattern": false
  }' \
  http://127.0.0.1:8080/api/measurements/background-oriented-schlieren/target/manifest
```

For the production download, supply the configured Fresnel credentials through
the HTTP client or calling environment. No credential values belong in a job or
in this documentation.

## Scientific claim boundary

This target supports a **qualitative BOS experiment**. BOS observes apparent
image-plane displacement associated with refractive-index gradients. By itself
it is not:

- a thermal camera;
- a temperature, density or pressure measurement;
- a velocity measurement;
- a direct measurement of airflow direction.

Camera motion, rolling shutter, compression, focus changes, target motion and
heat shimmer outside the intended region can all produce misleading
structures. Check the registration residual and rejected windows before interpreting a map.

## Verification

The implementation is exercised from the normal Maven reactor:

```bash
mvn -B -ntp -pl measurement-core,backend -am test
mvn -B -ntp -Dfresnel.e2e.skip=false verify
```

Tests cover deterministic pixels and hashes, seed changes, spacing and fill
bounds, quiet borders, asymmetric fiducials, inversion, immutable manifests,
PNG decoding, physical-resolution metadata, public preview access,
authenticated export, registry/job algorithm-version separation and cross-field
resource rejection.

## Next slices

Remaining work for issue #140 includes the following.

1. Analyze ordered sequences and video with temporal confidence and cancellation.
2. Integrate paired capture directly into the acquisition and analysis editor.
