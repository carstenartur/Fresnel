# Optical Design Assistant

> **This assistant is advisory.**
> All recommendations are based on the paraxial thin-lens approximation and the
> physical assumptions stated below. Verify every design experimentally before use.

## Purpose

The Optical Design Assistant bridges the gap between a user's practical goal and the
concrete Fresnel design parameters needed to achieve it. Given a printer resolution,
page size, light source wavelength, and target focal distance, it:

1. Generates several plausible Zone Plate design candidates.
2. Evaluates each candidate for optical quality, printability and fabrication risk.
3. Returns a ranked list and recommends the best option with a human-readable explanation.

## Endpoint

```
POST /api/assistant/recommend
Content-Type: application/json
```

### Request – `DesignGoalRequest`

| Field               | Type     | Required | Description                                      |
|---------------------|----------|----------|--------------------------------------------------|
| `dpi`               | `number` | yes      | Printer resolution in dots per inch              |
| `pageSizeWidthMm`   | `number` | yes      | Printable page width in mm                       |
| `pageSizeHeightMm`  | `number` | yes      | Printable page height in mm                      |
| `wavelengthNm`      | `number` | yes      | Design wavelength in nm (e.g. 532 for green laser)|
| `targetFocusMm`     | `number` | yes      | Target focal distance in mm                      |
| `maxApertureMm`     | `number` | no       | Optional hard cap on aperture diameter in mm     |

### Response – `AssistantRecommendationResponse`

| Field             | Type                   | Description                                     |
|-------------------|------------------------|-------------------------------------------------|
| `recommended`     | `CandidateDesignDto`   | Highest-ranked candidate                        |
| `alternatives`    | `CandidateDesignDto[]` | Remaining candidates, rank 2 onward             |
| `globalWarnings`  | `AssistantWarning[]`   | Advisory warnings about the whole recommendation|

Each `CandidateDesignDto` contains:

| Field            | Type                       | Description                                |
|------------------|----------------------------|--------------------------------------------|
| `label`          | `string`                   | Human-readable name                        |
| `parameters`     | `SingleZonePlateParameters`| Zone plate design parameters               |
| `rank`           | `int`                      | 1-based rank (1 = best)                    |
| `compositeScore` | `double`                   | Normalized composite score in [0, 1]       |
| `reasons`        | `RecommendationReason[]`   | Per-dimension scoring notes                |
| `warnings`       | `AssistantWarning[]`       | Design-specific warnings                   |
| `validation`     | `ValidationResponse`       | Full metrics and optical quality report    |

## Example — first vertical slice

**Goal:** 600 dpi, A4 transparency film, green laser (532 nm), target focus 2 m.

### Request

```json
{
  "dpi": 600,
  "pageSizeWidthMm": 210,
  "pageSizeHeightMm": 297,
  "wavelengthNm": 532,
  "targetFocusMm": 2000
}
```

### Response (abbreviated)

```json
{
  "recommended": {
    "label": "Compact Zone Plate (D = 5.0 mm)",
    "rank": 1,
    "compositeScore": 0.7,
    "parameters": {
      "apertureDiameterMm": 5.0,
      "focalLengthMm": 2000.0,
      "wavelengthNm": 532.0,
      "dpi": 600.0,
      "maskType": "BINARY_AMPLITUDE",
      "polarity": "POSITIVE"
    },
    "reasons": [
      { "dimension": "printability",    "description": "5.0 px per outer zone — good printability at 600 dpi" },
      { "dimension": "focus_quality",   "description": "NA = 0.00125, Airy disk = 1302 µm, depth of focus = 52041 µm" },
      { "dimension": "zone_adequacy",   "description": "13 Fresnel zones — adequate diffraction quality" },
      { "dimension": "fabrication_risk","description": "No printability warnings — low fabrication risk" },
      { "dimension": "physical_size",   "description": "Aperture diameter 5.0 mm fits on the specified page" }
    ],
    "warnings": [],
    "validation": { "valid": true, "warnings": [], ... }
  },
  "alternatives": [
    { "label": "Balanced Zone Plate (D = 7.1 mm)", "rank": 2, ... },
    { "label": "Wide-Aperture Zone Plate (D = 10.1 mm)", "rank": 3, ... }
  ],
  "globalWarnings": [
    {
      "code": "ADVISORY",
      "message": "This recommendation is advisory and based on stated physical assumptions ..."
    }
  ]
}
```

## Scoring model

The composite score is rule-based and deterministic. Four dimensions are computed for
every candidate, normalized to [0, 1] across the candidate set, and combined:

| Dimension          | Weight | Metric                                     |
|--------------------|--------|--------------------------------------------|
| Printability       | 40 %   | Pixels per outermost Fresnel zone          |
| Focus quality      | 30 %   | Numerical aperture (NA)                    |
| Zone adequacy      | 20 %   | Total number of Fresnel zones              |
| Fabrication risk   | 10 %   | 1.0 = no warnings, 0.5 = warning, 0.0 = error |

Ties are broken by submission order (deterministic).

## Candidate generation

Three aperture diameters are generated for each Zone Plate recommendation:

| Candidate      | Target px / outer zone | Typical result |
|----------------|------------------------|----------------|
| Compact        | 5.0 (recommended min)  | Best printability, fewest zones |
| Balanced       | 3.5                    | Medium trade-off |
| Wide-aperture  | 2.5                    | Most zones, near printability warning |

All apertures are clamped to `min(pageSizeWidthMm, pageSizeHeightMm)` and
`maxApertureMm` if provided.

## Physical assumptions

- **Paraxial thin-lens approximation** — zone radii follow $r_n = \sqrt{n \lambda f}$.
- **Binary amplitude mask** — transmission ≈ 50 %, first-order efficiency ≈ 1/π².
- **On-axis design** — off-axis aberrations are not modelled.
- **Monochromatic light** — chromatic focal shift is reported but not optimised.
- **Printer fidelity** — a pixel is assumed to be a perfect square dot. Real printers
  may have lower effective resolution due to dot gain and ink spread.
- **No wave-optical simulation** — propagation is not simulated; use the
  [Propagation Preview](../plugins/zone-plate.md) for that.

## Related

- Plugin metadata: [PluginRegistry](../../optics-core/src/main/java/org/fresnel/optics/PluginRegistry.java)
- Comparison and ranking primitives: [Design comparison](../compare.md) (issue #42)
- Validation reports: [Design validation](../validation.md) (issue #54)
