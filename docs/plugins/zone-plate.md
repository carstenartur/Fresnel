# Plugin: Zone Plate (`ZonePlateRenderer`)

A **Fresnel zone plate** is a diffractive optical element that focuses light by
alternately blocking or shifting the phase of concentric ring zones. This plugin
computes the binary amplitude or greyscale phase mask for a single, rotationally
symmetric (on-axis) or off-axis zone plate.

## Parameters

| Parameter | Unit | Description |
|-----------|------|-------------|
| `apertureDiameterMm` | mm | Outer diameter of the zone plate |
| `focalLengthMm` | mm | Design focal length (z-distance to image plane) |
| `wavelengthNm` | nm | Design wavelength |
| `dpi` | dots/inch | Printer / plotter resolution |
| `targetOffsetXmm` | mm | Off-axis target shift in X (0 = on-axis) |
| `targetOffsetYmm` | mm | Off-axis target shift in Y (0 = on-axis) |
| `maskType` | — | `BINARY_AMPLITUDE` or `GREYSCALE_PHASE` |
| `polarity` | — | `POSITIVE` (transparent zones) or `NEGATIVE` (inverted) |

## Mask types

| Type | Description |
|------|-------------|
| `BINARY_AMPLITUDE` | Classic zone plate: opaque and transparent rings. Theoretically ~10 % first-order efficiency. |
| `GREYSCALE_PHASE` | Continuous phase ramp (0–2π mapped to 0–255). Theoretically ~40 % first-order efficiency. |

## Example images

### On-axis, binary amplitude, positive polarity

![Zone plate — on-axis binary amplitude](../assets/plugins/zone-plate/on-axis.png)

Typical Fresnel zone plate: the innermost zone is transparent; successive zones
alternate opaque / transparent.

### Greyscale phase mask

![Zone plate — greyscale phase](../assets/plugins/zone-plate/greyscale-phase.png)

Continuous greyscale encoding of the phase 0…2π.  Brighter pixels correspond to
a larger phase shift.

### Negative polarity (inverted binary amplitude)

![Zone plate — negative polarity](../assets/plugins/zone-plate/negative-polarity.png)

Every transparent zone becomes opaque and vice versa.  The first-order focal spot
is the same; only the zero-order background changes.

## Validation workflow

Zone plate designs support two deterministic validation surfaces:

- `POST /api/designs/validate` (legacy single-zone response as `ValidationResponse`)
- `POST /api/designs/zone-plate/validation` (plugin-independent
  `DesignValidationReport` used across all plugins)

Both include optical and printability signals with explicit units and assumptions.
The plugin-independent report additionally keeps validation layers/finding semantics
consistent with the experimental workflow and export payloads.

### Optical quality fields and formulas

All formulas assume a paraxial diffractive optic working in air (n = 1).

| Field | Unit | Formula |
|-------|------|---------|
| `wavelengthNm` | nm | design wavelength (input) |
| `focalLengthMm` | mm | design focal length (input) |
| `apertureDiameterMm` | mm | aperture diameter (input) |
| `numericalAperture` | — | `NA = D / (2·f)` |
| `fNumber` | — | `F# = f / D` |
| `airyDiskDiameterMicrons` | µm | `d_Airy = 2.44·λ·F#` — diameter to first dark ring |
| `rayleighAngularResolutionRad` | rad | `θ_R = 1.22·λ/D` — classical Rayleigh criterion |
| `depthOfFocusMicrons` | µm | `DoF = 2·λ·F#²` — ±1λ wave-front-error criterion |
| `outermostZoneWidthMicrons` | µm | `Δr = λ·f/D` — paraxial outer zone approximation |
| `chromaticFocalShiftMm` | mm | `Δf = f·λ·(1/λ_min − 1/λ_max)` — from f(λ) ∝ 1/λ |
| `chromaticRangeMinNm` | nm | lower bound of chromatic shift estimate (default 450) |
| `chromaticRangeMaxNm` | nm | upper bound of chromatic shift estimate (default 650) |

### Java API

```java
SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(10.0, 1000.0, 550.0, 1200.0);

// Default visible range (450–650 nm)
OpticalQualityReport report = DesignValidator.computeOpticalQualityReport(p);

// Custom wavelength range
OpticalQualityReport report2 = DesignValidator.computeOpticalQualityReport(p, 500.0, 600.0);

System.out.println("NA = "  + report.numericalAperture());
System.out.println("F# = "  + report.fNumber());
System.out.println("Airy disk = " + report.airyDiskDiameterMicrons() + " µm");
```



```java
// On-axis convenience constructor
SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(
        10.0,   // aperture diameter, mm
        1000.0, // focal length, mm
        550.0,  // wavelength, nm
        1200.0  // DPI
);
RenderResult result = ZonePlateRenderer.render(p);
BufferedImage image  = result.image();
double pixelMm       = result.pixelSizeMm();

// Full constructor with off-axis target and greyscale phase
SingleZonePlateParameters p2 = new SingleZonePlateParameters(
        10.0, 1000.0, 550.0, 1200.0,
        2.0, 0.0,                          // target offset X/Y (mm)
        MaskType.GREYSCALE_PHASE,
        Polarity.POSITIVE
);
```

## Regenerating the example images

```bash
mvn -pl optics-core test -Dtest=PluginDocImagesTest#zonePlate_generateDocImages -Dfresnel.docs=generate
```

## Print calibration sheet

Before printing optical masks, download **Calibration PDF** from the Zone Plate panel.
The sheet contains:

- 0–100 mm scale bar
- centring cross and corner registration marks
- DPI/pixel-pitch stripe fields
- 1–4 px line-space targets
- density patches and circular aperture references
- metadata line with intended DPI and print scale

Always print at **100% / actual size** (disable fit-to-page).

### Interpreting common print errors

| Observation | Likely cause |
|---|---|
| Scale bar too short/long | driver or viewer scaling not at 100% |
| Horizontal and vertical scales differ | non-uniform scaling in print pipeline |
| 1 px / 2 px fields blur together | printer/material cannot resolve requested DPI |
| Corner marks offset between passes | registration/feed alignment error |
| Filled patch looks streaky or porous | insufficient density/ink or uneven toner transfer |
