# Optical Propagation Preview (`PropagationSimulator`)

The propagation preview simulates the scalar diffraction intensity at a given
distance from the printed mask.  It turns the application from a mask generator
into a design-check workflow: compare the printed pattern with the expected focal
spot, defocus pattern, or diffraction artefacts *before* exporting to print.

## How it works

1. **Mask → complex field** — the greyscale mask image is converted to a
   monochromatic complex amplitude:

   | `maskType` | Conversion |
   |------------|-----------|
   | `BINARY_AMPLITUDE` | amplitude = pixel / 255 (0 → opaque, 1 → transparent), phase = 0 |
   | `GREYSCALE_PHASE`  | unit amplitude, phase = pixel / 255 × 2π |

2. **Zero-padding** — the complex field is embedded in a square, zero-padded
   array whose side is the next power of two ≥ the mask side.  This avoids
   cyclic wrap-around artefacts in the FFT.

3. **Propagation** — one of two scalar methods is applied (see below).

4. **Intensity normalisation** — the output intensity |U|² is normalised to
   0–255 greyscale.  Only relative intensity within a single image is meaningful;
   different parameter sets cannot be compared directly.

## Propagation modes

### `FRESNEL_TF` — angular-spectrum transfer function

The field is propagated by the exact free-space transfer function:

```
H(fx, fy) = exp(i · 2π/λ · z · √(1 − (λ fx)² − (λ fy)²))
```

Evanescent components where `(λ fx)² + (λ fy)² > 1` are zeroed.  This method:

- Is valid for **any positive propagation distance** z.
- Preserves the spatial layout: the output pixel grid has the same physical
  pixel size as the input mask.
- Correctly shows the focal spot at z = f for a well-sampled lens element.

### `FRAUNHOFER` — far-field |FFT|²

Computes `|FFT(field)|²` with DC shifted to the output centre (fftshift).
This gives the **far-field / focal-plane diffraction pattern** and is equivalent
to observing the field at the back focal plane of an ideal thin lens placed
immediately after the mask.

- No physical distance is used in the computation; `zMm` is ignored.
- Best suited for qualitative inspection of the diffraction order structure.
- The centre pixel corresponds to the on-axis (DC) component.

## Approximations and limits

| Aspect | Status |
|--------|--------|
| Scalar (non-polarised) diffraction only | ✔ implemented |
| Monochromatic plane-wave illumination | ✔ |
| No material / absorption model | — |
| No vector diffraction | Not in v1 |
| No polarisation modelling | Not in v1 |
| No printer / material calibration | Not in v1 |

### Sampling requirement

Both methods require that the zone structure is **resolved by the pixel grid**.
The critical condition is that the outer-zone half-period must span at least
**two pixels**.  For a zone plate with aperture diameter *D*, focal length *f*,
wavelength λ, and printer resolution *p* (mm/pixel):

```
outer zone half-period  =  λ f / (2 D)  ≥  2 p
         ⟹  DPI  ≥  25.4 · D / (λ · f)  (in SI-mixed units)
```

For typical desktop printers (300–1200 DPI) this condition is satisfied only
for designs with very large f/D ratios or very small apertures.  When the
outer zones are sub-pixel, the simulation will still run but the focused-spot
intensity will be reduced; treat results as qualitative.

## REST API

```
POST /api/designs/propagate.png
Content-Type: application/json

{
  "base": { ... SingleZonePlateRequest ... },
  "zMm": 50.0,
  "mode": "FRESNEL_TF",       // optional, default FRESNEL_TF
  "wavelengthNm": 550.0       // optional, overrides base wavelength
}
```

Returns a PNG image of the propagated intensity.  The same
`MAX_PREVIEW_PX = 4096` pixel cap applies as for the mask preview; requests
for larger masks return HTTP 413.

## Frontend

In the **Zone Plate** panel, the *Optical propagation preview* section (below
the design metrics) lets you set the propagation distance and mode, then click
**Compute propagation** to visualise the result inline.

## Java API

```java
SingleZonePlateParameters zp = SingleZonePlateParameters.onAxis(
        10.0, 1000.0, 550.0, 1200.0);
RenderResult mask = ZonePlateRenderer.render(zp);

PropagationParameters p = new PropagationParameters(
        mask.image(),
        MaskType.BINARY_AMPLITUDE,
        mask.pixelSizeMm(),   // physical pixel size in mm
        550.0,                // wavelength in nm
        1000.0,               // propagation distance z in mm
        PropagationMode.FRESNEL_TF);

RenderResult intensity = PropagationSimulator.propagate(p);
```
