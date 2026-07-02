# Experimental validation workflow

The first experimental-validation workflow in Fresnel is intentionally simple:

1. Select a generated design in the **Single ZP** panel.
2. Fill in the print / illumination setup.
3. Enter the measured focal length, spot size and notes.
4. Compare the measured focus against the predicted focus.
5. Export the record as JSON or Markdown.

The backend model consists of:

- `ExperimentRecord`
- `ExperimentSetup`
- `MeasurementResult`
- `MeasuredFocus`
- `ExperimentalComparison`

The REST endpoints are:

- `POST /api/experiments/compare`
- `POST /api/experiments/export.json`
- `POST /api/experiments/export.md`

## Complete example experiment

```json
{
  "designId": "lab-book-2026-07-02-zp-01",
  "pluginId": "zone-plate",
  "parameterHash": "zp-demo-hash",
  "designDocument": {
    "kind": "single",
    "version": 1,
    "payload": {
      "apertureDiameterMm": 10.0,
      "focalLengthMm": 1000.0,
      "wavelengthNm": 550.0,
      "dpi": 1200.0,
      "maskType": "BINARY_AMPLITUDE",
      "polarity": "POSITIVE"
    }
  },
  "validationReport": {
    "pluginId": "zone-plate",
    "parameterHash": "zp-demo-hash",
    "parameterSnapshot": {
      "apertureDiameterMm": "10",
      "focalLengthMm": "1000",
      "wavelengthNm": "550",
      "dpi": "1200"
    },
    "wavelengthMinNm": 550.0,
    "wavelengthMaxNm": 550.0,
    "apertureDiameterMm": 10.0,
    "targetFocalDistancesMm": [1000.0],
    "pixelSizeMicrons": 21.167,
    "assumptions": [],
    "metrics": [],
    "findings": []
  },
  "setup": {
    "printerModel": "Epson EcoTank ET-1810",
    "nominalDpi": 1200.0,
    "effectiveDpi": 1140.0,
    "materialType": "Transparent PET foil",
    "exposureSettings": "Matte photo / high quality",
    "lightSourceType": "Green LED",
    "wavelengthNm": 532.0,
    "spectrumEstimate": "narrow-band LED",
    "environmentalNotes": "Bench test at room temperature",
    "photoReferences": ["focus-setup.jpg", "spot-closeup.jpg"]
  },
  "measurement": {
    "targetFocalLengthMm": 1000.0,
    "measuredFoci": [
      {
        "label": "Primary focus",
        "measuredFocalLengthMm": 1025.0,
        "measuredSpotSizeMicrons": 180.0,
        "focusRating": "Sharp center, faint halo",
        "notes": "Peak intensity located by camera rail"
      }
    ]
  },
  "comparison": {
    "targetFocalLengthMm": 1000.0,
    "measuredFocalLengthMm": 1025.0,
    "focalLengthErrorMm": 25.0,
    "focalLengthErrorPercent": 2.5,
    "measuredSpotSizeMicrons": 180.0,
    "focusRating": "Sharp center, faint halo",
    "summary": "Measured focal length 1025.000 mm vs target 1000.000 mm (+25.00 mm, +2.50%). Spot size: 180.000 µm. Focus rating: Sharp center, faint halo."
  }
}
```
