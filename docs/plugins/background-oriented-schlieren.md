# Background-Oriented Schlieren

**Plugin ID:** `background-oriented-schlieren`  
**Kind:** `MEASUREMENT`  
**Stability:** `EXPERIMENTAL`  
**Current algorithm:** `background-oriented-schlieren-target/1`

Background-Oriented Schlieren (BOS) makes small apparent movements of a
high-contrast background visible. In a typical household experiment, a camera
first records a random-dot target without the disturbance and then records the
same target with warm or moving air between camera and target.

The current implementation is the first complete target-generation slice. It
creates, previews, downloads and describes a deterministic target. Capture
import, image registration and displacement analysis remain separate later
slices; Fresnel does not yet claim to produce a Schlieren result from camera
images in this plugin.

## What is available now

The trusted built-in plugin provides:

- a bounded, deterministic random-dot generator in `measurement-core`;
- a fixed, versioned pseudo-random sequence independent of JDK random classes;
- a quiet border excluded from the future analysis region;
- four asymmetric orientation fiducials outside that active region;
- lossless grayscale PNG output with physical DPI metadata;
- a data-only manifest with target geometry, seed, dot statistics and semantic
  SHA-256;
- schema-driven editing and portable `.fresnel` jobs;
- a public bounded preview and manifest endpoint;
- an authenticated production PNG download.

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

Changing the seed changes dot placement. Keeping every parameter unchanged
reproduces the same pixels, semantic hash and target ID.

## Screen target

Use a lossless viewer or Fresnel's generated PNG. Disable interpolation where
the viewer permits it. Browser zoom, operating-system scaling and device pixel
ratio can prevent one CSS pixel from corresponding to one physical display
pixel, so record the actual presentation setup in the experiment notes.

The target must remain stationary and unchanged between the reference and
measurement captures. Do not place browser controls, labels or a pointer over
the active dot region.

## Printed target

The PNG contains a `pHYs` chunk derived from `intendedDpi`. Print at **100%** and
disable:

- fit-to-page or shrink-to-printable-area;
- image enhancement and smoothing;
- driver resampling;
- automatic border or poster scaling.

The manifest reports the intended width and height in millimetres. Measure the
printed target before treating its geometry as known. A later slice will add
vector/PDF print targets and an explicit scale strip; this first slice exports a
lossless physically tagged PNG only.

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

The manifest records:

- algorithm version and stable target ID;
- semantic SHA-256 over parameters, geometry and exact grayscale pixels;
- source dimensions, intended DPI and physical size;
- seed, requested and actual fill ratio, dot count and cell pitch;
- active-region rectangle;
- fiducial identities, shapes and rectangles;
- inversion and border settings.

The PNG response repeats the target ID and hash in headers and uses the semantic
hash as its ETag. The hash describes the target semantics and pixels, not a
particular HTTP transfer.

## HTTP API

Public bounded operations:

```text
POST /api/measurements/background-oriented-schlieren/target/manifest
POST /api/measurements/background-oriented-schlieren/target/preview.png
```

Authenticated production download:

```text
POST /api/measurements/background-oriented-schlieren/target/export.png
```

All operations accept the same JSON parameter object. The PNG endpoints return:

```text
ETag
X-Fresnel-Target-SHA256
X-Fresnel-Target-Id
X-Fresnel-Active-Region
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
structures. The future analysis slice must estimate global camera motion and
reject poor registration rather than displaying it as local air motion.

## Verification

The implementation is exercised from the normal Maven reactor:

```bash
mvn -B -ntp -pl measurement-core,backend -am test
mvn -B -ntp -Dfresnel.e2e.skip=false verify
```

Tests cover deterministic pixels and hashes, seed changes, spacing and fill
bounds, quiet borders, asymmetric fiducials, inversion, immutable manifests,
PNG decoding, physical-resolution metadata, public preview access,
authenticated export and cross-field resource rejection.

## Next slices

The next implementation work for issue #140 is deliberately separate from the
target generator:

1. evaluate a reference image for dot resolution, contrast, clipping and active
   region coverage;
2. add bounded ordered manual upload of reference and disturbed still images;
3. implement global registration and deterministic local displacement
   estimation with numeric synthetic tests;
4. add the durable measurement-session shell from issue #139;
5. advertise `IMPORT_CAPTURE_SET`, `ANALYZE_CAPTURE_SET` and `REMOTE_CAPTURE`
   only when those paths are actually implemented and tested.
