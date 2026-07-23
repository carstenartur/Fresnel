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
you preview or export the same design as PNG, SVG, PDF or native PCL. Each example
also contains an executable SVG production plan, so the command-line job runner
can generate an independently printable orientation file without Java test code.

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
printable bounds, media command, page orientation, page-X/page-Y mapping and the
supported compression modes. The profile is code-owned and versioned. Job files
and HTTP requests can select its identifier but cannot provide arbitrary PCL
commands or escape sequences.

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
4. Keep unused padding bits at the end of each row clear.
5. Optionally compress each row with deterministic TIFF/PackBits compression.
6. Emit only commands selected by the trusted PCL 5e profile.

The output includes reset, profile-selected media, page orientation, raster
resolution, printable origin, raster width/height, compression mode, raster
start/end, form feed and final reset commands. Repeated generation from identical
normalized parameters and profile produces identical bytes.

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

The automated test suite parses the generated PCL stream, decodes every row and
compares every addressed dot and padding bit against the source raster for both
compression modes. A separate golden SHA-256 test detects command or byte drift.

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

Raster resource limits are applied when a raster is actually requested. A design
can therefore retain a high nominal DPI for its metadata while still being
exported safely as SVG or through a lower-resolution trusted PCL profile. PNG/PDF
requests that would exceed the bounded pixel budget fail explicitly.

## Analysis and validation

The plugin reports orientation-aware metrics:

- active-area and annotation-area physical bounds
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

## Recording and exporting physical measurements

`PrinterCalibrationResult` is the reusable first-class result contract for one
printed orientation. It records:

- printer model, trusted profile ID and profile version
- medium or transparency description and driver quality mode
- nominal X/Y DPI
- page orientation and page-to-device axis mapping
- selected line orientation and tested device axis
- observed degradation position along the calibrated axis
- first repeatably resolved pitch and derived lines/mm
- minimum useful opaque/clear feature width and derived effective DPI
- observation notes
- optional photo or measurement attachment reference
- measurement timestamp

The graphical editor includes **Record physical calibration result**. Enter one
observation for vertical lines and export it as JSON, then repeat with a separate
horizontal job. The backend verifies that the stored profile version, DPI and
axis mapping still match the trusted profile before it returns the file:

```text
POST /api/designs/variable-line-grating/calibration-results/export.json
```

A software-only render does not prove physical resolution. Toner spread, ink
bleed, media transport, transparency dimensional change and driver processing
remain experimental inputs and belong in the measurement record.

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
8. Export separate calibration-result JSON files for the two orientations.

A viewer or print driver can silently alter SVG, PNG or PDF. The native PCL path
reduces that uncertainty but does not eliminate printer firmware processing or
physical material limits.

## Suggested optical experiments

These experiments are optional and do not replace direct microscope, loupe or
camera inspection of the printed line structure.

### Diffuse window or light-panel inspection

1. Print the two orientation targets on the intended transparent medium.
2. Place one target against a uniformly illuminated window, diffuse LED panel or
   other broad, non-hazardous light source.
3. Photograph the target perpendicular to the sheet with fixed focus, exposure
   and magnification.
4. Move from the coarse-pitch end toward the fine-pitch end and identify the first
   position where opaque and clear bands no longer remain repeatably separable.
5. Repeat with the second orientation without changing camera or illumination
   settings.

This primarily measures the printer/material/camera chain. It is the recommended
starting point because the calibrated axis can be read directly in the same
image.

### Point-light diffraction and optional wall projection

1. Use a low-power, diffuse LED behind a small aperture or another enclosed,
   non-hazardous point-like source.
2. Place the grating between the source and a white wall or matte screen.
3. Keep source, target and screen fixed and document all distances.
4. Observe or photograph how the diffraction structure changes along the
   variable-pitch axis.
5. Repeat separately for vertical and horizontal lines; the spread direction
   should rotate by 90 degrees.

Wall projection is qualitative unless geometry, wavelength spectrum and camera
response are measured. It must not be interpreted as a direct DPI value without
the printed-line inspection and recorded calibration axis.

## Eye and source safety

- **This target is not solar-protection material, a solar filter, protective
  eyewear or a certified optical attenuator.**
- **Never look at the Sun through the printed target and never place it in front
  of a telescope, binoculars, camera viewfinder or other concentrating optic.**
- Do not view lasers, high-power LEDs, arc lamps, welding sources or other
  hazardous emitters directly or through the grating.
- Use only low-power, enclosed or diffusely illuminated sources for qualitative
  projection experiments. Observe the wall or screen, not the source.
- A printed dark area can transmit invisible infrared or ultraviolet radiation;
  visual darkness is not evidence of eye safety.
- Follow the printer and media manufacturer's ventilation, handling and fire
  precautions, especially for transparency films.

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

PrinterCalibrationResult result = PrinterCalibrationResult.fromProfile(
        "Printer model",
        profile,
        "Transparency film",
        "Maximum quality",
        parameters.lineOrientation(),
        132.5,
        84.0,
        42.0,
        1000.0 / 84.0,
        25_400.0 / 42.0,
        "Bands remain repeatable up to the recorded position.",
        "photos/vertical-calibration.jpg",
        java.time.Instant.now());
```

## Security and resource limits

- Width, height, pitch, duty cycle, annotation sizes, tick count and DPI are
  bounded by the public schema and backend validation.
- Raster creation has an explicit pixel-count limit at the actual output DPI.
- Output filenames remain portable basenames in `.fresnel` production plans.
- PCL options are field-whitelisted.
- Printer profiles and media commands are trusted code resources, not
  user-provided command text.
- Calibration-result exports verify their profile snapshot before serialization.
- Unsupported profiles, media sizes, dialects, compression modes or
  non-representable DPI combinations fail explicitly.
