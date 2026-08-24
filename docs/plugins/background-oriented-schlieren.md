# Background-Oriented Schlieren

The `background-oriented-schlieren` measurement plugin begins with a deterministic random-dot target. A complete parameter record and the pattern seed identify one reproducible image. This makes the target suitable for a checked-in `.fresnel` job, a capture plan and later reference/disturbed image comparisons.

## Current implementation slice

This slice provides:

- a bounded seeded random-dot generator;
- an exact minimum dot-spacing constraint;
- a configurable registration border and optional corner fiducials;
- normal and inverted binary polarity;
- a browser preview;
- a full-resolution PNG export;
- provenance headers containing the seed, placed-dot count and achieved fill ratio.

The plugin currently advertises only the capabilities that are implemented end to end:

- `GENERATE_CAPTURE_TARGET`
- `PREVIEW_PNG`
- `EXPORT_PNG`

Capture import, Photographer-controlled acquisition, displacement analysis, live result previews and experiment-bundle export are not advertised yet.

## Reproducibility

The following fields determine the target:

| Parameter | Meaning |
|---|---|
| `widthPx`, `heightPx` | Authoritative raster dimensions |
| `intendedDpi` | Intended physical use; it does not resample the raster |
| `patternSeed` | Seed for deterministic dot placement |
| `dotDiameterPx` | Binary dot diameter |
| `targetFillRatio` | Requested nominal dot-area fraction |
| `minimumDotSpacingPx` | Required clear spacing between dot boundaries |
| `borderPx` | Dot-free registration border |
| `fiducialsEnabled` | Draw corner registration marks inside the border |
| `invertPattern` | Swap foreground and background polarity |

For identical parameters the exported pixels are identical. Changing the seed changes the dot locations while retaining the requested dot count.

The renderer never silently reduces spacing or dot size. If the requested fill ratio cannot be achieved with the selected geometry, generation fails with a validation error. This avoids producing a target that looks plausible but violates the recorded experiment parameters.

## Generate a target in the web interface

1. Open **Background-Oriented Schlieren** in the plugin navigation.
2. Select the raster dimensions and intended output resolution.
3. Set the dot diameter, fill ratio and minimum spacing.
4. Keep the generated seed or enter a known seed for exact reproduction.
5. Render a preview to inspect density, border and fiducials.
6. Export the full-resolution PNG.

Do not scale, smooth or otherwise resample the exported target when printing or displaying it. The preview may be reduced for browser display; the exported PNG is the authoritative capture target.

## HTTP API

Preview:

```http
POST /api/designs/background-oriented-schlieren/preview.png
Content-Type: application/json
Accept: image/png
```

Full-resolution export:

```http
POST /api/designs/background-oriented-schlieren/export.png
Content-Type: application/json
Accept: image/png
```

The response includes:

```text
X-Fresnel-Pattern-Seed
X-Fresnel-Dot-Count
X-Fresnel-Achieved-Fill-Ratio
```

These headers are convenient diagnostics. Durable measurement sessions will additionally persist the canonical parameter object and an SHA-256 identity for the exact target asset.

## Planned measurement lifecycle

The next BOS slices use the common measurement architecture rather than embedding camera logic in the renderer:

```text
parameters
  -> immutable target asset
  -> reference capture
  -> disturbed capture set
  -> displacement analysis
  -> visualization and experiment bundle
```

Manual upload remains the baseline capture route. Photographer integration is optional and goes through the provider-neutral capture contract; Fresnel does not depend on Photographer implementation classes, its filesystem or its database.

See also:

- `docs/measurement-plugins.md`
- issue #139 for the common session and artifact lifecycle
- issue #140 for the complete BOS plugin
- Photographer issue #314 for the remote capture boundary
