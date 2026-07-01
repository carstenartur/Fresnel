# Parameter study: comparing zone-plate designs

This page shows how to compare two or more design variants programmatically using the
`POST /api/designs/compare` endpoint introduced in Fresnel 0.1.5.

---

## Background

Choosing the right zone-plate parameters (aperture, focal length, DPI) is a trade-off
between optical resolution and printability.  A shorter focal length raises the
numerical aperture and reduces the Airy-disk diameter, but it also shrinks the
outermost zone width below the printer's resolution.  The comparison endpoint makes
these trade-offs visible side-by-side and, optionally, ranks the variants
automatically.

---

## Quick example — two single zone plates

```json
POST /api/designs/compare
Content-Type: application/json

{
  "variants": [
    {
      "label": "High-NA (f = 500 mm)",
      "pluginId": "single",
      "singleParams": {
        "apertureDiameterMm": 20.0,
        "focalLengthMm":     500.0,
        "wavelengthNm":      550.0,
        "dpi":              2400.0
      }
    },
    {
      "label": "Low-NA (f = 2000 mm)",
      "pluginId": "single",
      "singleParams": {
        "apertureDiameterMm": 20.0,
        "focalLengthMm":     2000.0,
        "wavelengthNm":       550.0,
        "dpi":               2400.0
      }
    }
  ],
  "rank": true
}
```

### Response structure

```json
{
  "variants": [
    {
      "label": "High-NA (f = 500 mm)",
      "pluginId": "single",
      "validation": {
        "valid": false,
        "warnings": [
          {
            "code": "OUTER_ZONE_TOO_SMALL",
            "message": "The outer zone width is only 0.26 printer pixels (need ≥ 2).",
            "severity": "ERROR"
          }
        ],
        "metrics": { ... },
        "qualityReport": { "numericalAperture": 0.02, "fNumber": 25.0, ... }
      },
      "previewBase64": "<base-64 PNG>",
      "previewWidthPx": 256,
      "previewHeightPx": 256,
      "pixelsPerMm": 94.49,
      "score": { "rank": 2, "score": 0.314, "explanation": "Composite score 0.314 — ..." }
    },
    {
      "label": "Low-NA (f = 2000 mm)",
      "pluginId": "single",
      "validation": {
        "valid": true,
        "warnings": [],
        "metrics": { ... },
        "qualityReport": { "numericalAperture": 0.005, "fNumber": 100.0, ... }
      },
      "previewBase64": "<base-64 PNG>",
      "previewWidthPx": 256,
      "previewHeightPx": 256,
      "pixelsPerMm": 94.49,
      "score": { "rank": 1, "score": 1.0, "explanation": "Composite score 1.000 — ..." }
    }
  ],
  "parameterDifferences": [
    { "parameter": "focalLengthMm", "unit": "mm", "values": ["500", "2000"] }
  ]
}
```

Key fields:

| Field | Description |
|---|---|
| `variants[].validation` | Full validation response (same as `POST /api/designs/validate`) |
| `variants[].previewBase64` | Base-64 encoded preview PNG; all variants share the same physical scale |
| `variants[].pixelsPerMm` | Physical scale common to every preview in this comparison |
| `variants[].score` | Ranking result (only present when `rank: true`) |
| `parameterDifferences` | Alphabetically sorted list of parameters that differ across variants |

---

## Parameter study — varying focal length

Send multiple variants in one request to sweep a parameter:

```bash
curl -s -X POST http://localhost:8080/api/designs/compare \
     -H 'Content-Type: application/json' \
     -d '{
  "variants": [
    { "label": "f=500",  "pluginId": "single",
      "singleParams": { "apertureDiameterMm": 20, "focalLengthMm":  500, "wavelengthNm": 550, "dpi": 2400 } },
    { "label": "f=1000", "pluginId": "single",
      "singleParams": { "apertureDiameterMm": 20, "focalLengthMm": 1000, "wavelengthNm": 550, "dpi": 2400 } },
    { "label": "f=2000", "pluginId": "single",
      "singleParams": { "apertureDiameterMm": 20, "focalLengthMm": 2000, "wavelengthNm": 550, "dpi": 2400 } },
    { "label": "f=5000", "pluginId": "single",
      "singleParams": { "apertureDiameterMm": 20, "focalLengthMm": 5000, "wavelengthNm": 550, "dpi": 2400 } }
  ],
  "rank": true
}' | python3 -m json.tool
```

Expected ranking order (highest printability wins): `f=5000` > `f=2000` > `f=1000` > `f=500`.

---

## Comparing zone-plate types

You can mix `"single"` and `"rgb"` variants in the same request:

```json
{
  "variants": [
    {
      "label": "Monochromatic",
      "pluginId": "single",
      "singleParams": {
        "apertureDiameterMm": 10.0,
        "focalLengthMm":     100.0,
        "wavelengthNm":      550.0,
        "dpi":               600.0
      }
    },
    {
      "label": "RGB tri-colour",
      "pluginId": "rgb",
      "rgbParams": {
        "base": {
          "apertureDiameterMm": 10.0,
          "focalLengthMm":     100.0,
          "wavelengthNm":      550.0,
          "dpi":               600.0
        },
        "redNm":   630.0,
        "greenNm": 532.0,
        "blueNm":  450.0
      }
    }
  ],
  "rank": false
}
```

The `parameterDifferences` section will list `pluginId` (and any wavelength parameters)
as differing entries.

---

## Ranking algorithm

When `rank: true` is set, each variant receives a composite score computed from three
normalized metrics:

| Component | Weight | Better when |
|---|---|---|
| Pixels per outer zone | 40 % | Higher (easier to print) |
| Numerical aperture (NA) | 30 % | Higher (finer resolution) |
| Reciprocal depth of focus | 30 % | Higher (tighter focus) |

Each component is divided by the maximum value in the comparison, so the best variant
in each category scores 1.0.  The final score is a weighted sum in [0, 1].

Ties are broken by submission order (the first variant wins ties).

---

## Acceptance criteria reference

This endpoint satisfies the following acceptance criteria from
[Fresnel#42](https://github.com/carstenartur/Fresnel/issues/42):

- **Backend accepts ≥ 2 configurations** — validated by `@Size(min = 2)` on `variants`.
- **Rendered previews with explicit scale metadata** — `previewBase64`, `previewWidthPx`,
  `previewHeightPx`, and `pixelsPerMm` are returned for every variant; all previews
  share the same physical scale.
- **Parameter differences in a stable, testable structure** — the `parameterDifferences`
  list is alphabetically sorted and uses the same canonical field names as the request DTOs.
- **Quality and printability warnings side-by-side** — each `variants[].validation`
  object contains the full printability metrics and optional quality report.
- **Deterministic ranking** — the algorithm is pure and tie-broken by index.
- **Frontend comparison view** — the *Compare* tab in the Fresnel Designer UI shows two
  or more variants with side-by-side previews, a parameter-difference table, and
  optional rank badges.
