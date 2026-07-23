# Plugin: Variable-Line Grating (`variable-line-grating`)

The **Variable-Line Grating** plugin generates a binary calibration target whose
local line pitch varies continuously across exactly one page axis. It is intended
to measure the effective spatial resolution of a printer/material/driver pipeline,
including cases where the two device axes behave differently.

A single generated output always contains one line family only:

- `VERTICAL`: lines run along page Y and pitch changes along page X.
- `HORIZONTAL`: lines run along page X and pitch changes along page Y.

This separation is deliberate. A vertical-line and a horizontal-line calibration
must be printed as two independent files so the tested page axis, mapped device
axis and selected device DPI remain unambiguous.

## Reproducible example jobs

- [Vertical lines — test page X](../../examples/variable-line-grating/vertical-lines.fresnel)
- [Horizontal lines — test page Y](../../examples/variable-line-grating/horizontal-lines.fresnel)

Open either file with Fresnel or choose **Open job…** in the web application. The
application selects the plugin, restores the orientation and parameters, and lets
you preview or export the same design as PNG, SVG, PDF or native PCL.

## Parameters

| Parameter | Unit | Meaning |
|---|---:|---|
| `widthMm` | mm | Physical output width |
| `heightMm` | mm | Physical output height |
| `lineOrientation` | — | `VERTICAL` or `HORIZONTAL`; exactly one is required |
| `startPitchUm` | µm | Local period at normalized progression position 0 |
| `endPitchUm` | µm | Local period at normalized progression position 1 |
| `progression` | — | `LINEAR_PITCH`, `LINEAR_SPATIAL_FREQUENCY`, or `LOGARITHMIC_PITCH` |
| `progressionDirection` | — | `NORMAL` or `REVERSED` |
| `dutyCycle` | fraction | Opaque fraction of one local period, strictly between 0 and 1 |
| `phaseOffsetCycles` | cycles | Global phase offset before thresholding |
| `polarity` | — | `POSITIVE` or `NEGATIVE` |
| `marginMm` | mm | Clear outer margin |
| `annotationSizeMm` | mm | Space reserved for axis labels and print instructions |
| `showAxis` | — | Adds the calibrated pitch/frequency/dot axis outside the optical region |
| `axisQuantity` | — | `PITCH_UM`, `LINES_PER_MM`, or `DEVICE_DOTS_PER_PERIOD` |
| `tickCount` | — | Number of calibrated axis ticks, 2–21 |
| `showReferenceBands` | — | Adds constant-pitch start/end comparison bands |
| `referenceBandSizeMm` | mm | Thickness of each comparison band |
| `dpi` | dpi | Ordinary preview, PNG and PDF rasterization resolution |

The parameter schema is versioned at
`fresnel/plugins/variable-line-grating/parameters-v1.schema.json`. Printer
profiles are intentionally not optical parameters; they are selected separately
for device-axis analysis and PCL production.

## Phase-correct line placement

The renderer does not evaluate a naive expression such as `coordinate % pitch`.
That approach introduces discontinuities whenever pitch changes. Instead, it
integrates local spatial frequency:

```text
cycles(s) = phaseOffsetCycles + ∫₀ˢ 1 / pitch(t) dt
```

The binary mask is then obtained from the fractional cycle phase and configured
duty cycle. This guarantees continuous phase and the correct accumulated number
of lines for all supported progression laws.

For normalized position `u ∈ [0,1]` and physical active length `L`, the plugin
supports:

### Linear pitch

```text
pitch(u) = p₀ + (p₁ - p₀)u
```

The reciprocal-pitch integral is logarithmic when `p₀ ≠ p₁`.

### Linear spatial frequency

```text
frequency(u) = 1/p₀ + (1/p₁ - 1/p₀)u
pitch(u) = 1 / frequency(u)
```

This makes the number of lines per millimetre vary linearly.

### Logarithmic pitch

```text
pitch(u) = p₀ (p₁/p₀)ᵘ
```

This distributes equal pitch ratios over equal distances. Reversed progression
uses the same integrated model with start and end positions exchanged; it does
not mirror an already rasterized image.

## Orientation and printer-axis mapping

The optical definition uses page coordinates. A versioned `PrinterRasterProfile`
maps those coordinates to native device axes:

| Grating orientation | Pitch varies along | Axis tested by analysis |
|---|---|---|
| Vertical lines | page X | device axis mapped from page X |
| Horizontal lines | page Y | device axis mapped from page Y |

The initial trusted profile is:

```text
pcl5e-a4-600-portrait-v1
```

It defines PCL 5e, A4 portrait, 600 × 600 device DPI, printable origin and
printable bounds, page orientation, page-X/page-Y mapping and the supported
compression modes. The profile is code-owned and versioned. Job files and HTTP
requests can select its identifier but cannot provide arbitrary PCL commands or
escape sequences.

The model supports separate X/Y DPI and explicit axis swapping. The initial PCL
5e encoder deliberately rejects an asymmetric profile because its selected
single raster-resolution command cannot represent different row and column DPI
without an additional validated dialect implementation. Analysis can still use
asymmetric profiles correctly.

## Native PCL output

`EXPORT_PCL` produces `application/vnd.hp-pcl` using a deterministic one-bit
raster path:

1. Convert physical sheet dimensions to page-axis device dots.
2. Evaluate one binary sample at each addressed dot.
3. Pack eight samples per byte, most-significant bit first.
4. Optionally compress each row with deterministic TIFF/PackBits compression.
5. Emit only commands selected by the trusted PCL 5e profile.

The output includes reset, A4 media, portrait orientation, raster resolution,
printable origin, raster width/height, compression mode, raster start/end, form
feed and final reset commands. Repeated generation from identical normalized
parameters and profile produces identical bytes.

Supported PCL compression values:

- `NONE`: uncompressed row bytes
- `TIFF`: PCL mode 2 / PackBits-style row compression

The HTTP endpoint is:

```text
POST /api/designs/variable-line-grating/export.pcl
```

Query parameters are limited to:

```text
printerProfileId=pcl5e-a4-600-portrait-v1
compression=TIFF
```

## PNG, SVG and PDF

- **PNG** uses the same binary raster model at the configured ordinary `dpi`.
- **SVG** writes physical millimetre dimensions and vector rectangles derived
  from the integrated phase model.
- **PDF** embeds a physical-scale raster and should be printed at actual size.
- **PCL** bypasses a graphics-driver conversion stage and addresses the trusted
  profile's device-dot raster directly.

SVG is the preferred editable/vector reference. PCL is the preferred output for
controlled device-dot experiments. PNG and PDF remain useful for visual checks
and common print workflows but can be resampled by viewers or drivers.

## Analysis and validation

The plugin reports orientation-aware metrics:

- selected page and device axis
- selected-axis DPI
- minimum and maximum pitch
- minimum opaque and clear feature widths
- minimum and maximum dots per period
- minimum dots per opaque and clear feature
- integrated nominal cycle count
- positions where the progression crosses 8, 4, 3 and 2 device dots per period

Pitch smaller than the nominal printer capability is legal. This is a calibration
plugin, so intentional undersampling produces a warning rather than a validation
error. The unresolved region is often the part of the target that carries the
measurement.

The validation endpoint is:

```text
POST /api/designs/variable-line-grating/validation
```

Add `printerProfileId` to evaluate the actual mapped device axis. Without a
profile, validation uses the design DPI and nominal page-axis mapping.

## Recording physical measurements

`PrinterCalibrationResult` is the reusable result contract for a printed target.
It records:

- printer profile ID and version
- selected line orientation
- tested device axis
- first resolved pitch or lines/mm
- derived effective DPI
- observation notes
- measurement timestamp

The current plugin exposes the contract but does not pretend that a software-only
render proves physical resolution. Toner spread, ink bleed, media transport,
transparency dimensional change and driver processing remain experimental inputs.

## Printing instructions

For a meaningful measurement:

1. Generate vertical and horizontal files separately.
2. Use the same media, printer state and quality settings for both.
3. Print at **100% / actual size**.
4. Disable **fit to page**, automatic rotation, image enhancement, smoothing and
   driver scaling.
5. For PCL, send the file only to a printer known to support the selected trusted
   profile and PCL dialect.
6. Confirm the physical axis scale before interpreting the smallest resolved
   bands.
7. Record the first repeatably resolved pitch, not a single isolated visible line.

A viewer or print driver can silently alter SVG, PNG or PDF. The native PCL path
reduces that uncertainty but does not eliminate printer firmware processing or
physical material limits.

## Java API

```java
VariableLineGratingParameters parameters = VariableLineGratingParameters.defaults();

RenderResult pngSource = VariableLineGratingRenderer.render(parameters);
byte[] svg = VariableLineGratingSvgExporter.toSvgBytes(parameters);

PrinterRasterProfile profile = PrinterRasterProfiles.require(
        PrinterRasterProfiles.DEFAULT_PROFILE_ID);
byte[] pcl = PclExporter.toPclBytes(parameters, profile, PclCompression.TIFF);

VariableLineGratingAnalysis.Result analysis =
        VariableLineGratingAnalysis.analyze(parameters, profile);
```

## Security and resource limits

- Width, height, pitch, duty cycle, annotation sizes, tick count and DPI are
  bounded by the public schema and backend validation.
- Raster creation has an explicit pixel-count limit.
- Output filenames remain portable basenames in `.fresnel` production plans.
- PCL options are field-whitelisted.
- Printer profiles are trusted code resources, not user-provided command text.
- Unsupported profiles, dialects, compression modes or non-representable DPI
  combinations fail explicitly.
