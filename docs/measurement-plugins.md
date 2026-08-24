# Measurement plugin architecture

Fresnel distinguishes two trusted plugin interaction models:

```text
DESIGN       deterministic optical-element rendering and export
MEASUREMENT  target generation, capture acquisition and image analysis
```

The distinction is published as `PluginMetadata.kind`. It does not enable
runtime code loading: implementations remain reviewed application code registered
through `PluginRegistry`, while parameter and UI schemas remain data-only.

## Current implementation status

Background-Oriented Schlieren is the first registered `MEASUREMENT` plugin. Its
initial target-generation slice is implemented end to end:

```text
schema-backed parameters
        → deterministic BosTargetGenerator
        → preview / lossless PNG
        → versioned manifest + semantic SHA-256
        → portable .fresnel job
```

It deliberately advertises only:

```text
GENERATE_CAPTURE_TARGET
PREVIEW_PNG
EXPORT_PNG
```

It does **not** yet advertise upload, remote capture, image analysis, live
analysis or experiment-bundle export. This keeps the metadata truthful while the
shared session and analysis layers are implemented separately. See
[Background-Oriented Schlieren](plugins/background-oriented-schlieren.md) for
the user workflow and scientific claim boundary.

## Why measurement workflows need a separate lifecycle

A design plugin can normally validate parameters and produce an artifact in one
request. A complete measurement plugin must preserve a multi-step experiment:

```text
DRAFT
  → TARGET_READY
  → CALIBRATING
  → READY_TO_CAPTURE
  → CAPTURING
  → READY_TO_ANALYZE
  → ANALYZING
  → COMPLETED
```

`FAILED` and `CANCELLED` are terminal alternatives. Backend persistence and the
measurement workflow UI will use these states so a browser reload, process
restart or temporary Photographer outage does not silently lose experiment
identity. The BOS target generator is intentionally stateless; durable sessions
begin when capture import or remote capture is added.

## Core boundary

The `measurement-core` Maven module contains only Java value contracts and
validation. It has no Spring, JPA, HTTP, frontend or camera-vendor dependency.

It now contains both the shared acquisition contracts and the deterministic BOS
target model. Raster encoding remains in `backend` because Java ImageIO and HTTP
content metadata are delivery concerns rather than scientific target semantics.

### Capture plans

`CapturePlan` describes a bounded ordered sequence. It contains:

- workflow type;
- selected provider device ID;
- exposure/focus lock preferences;
- preferred image format;
- immutable capture steps with role, sequence index, trigger mode, delay and
  optional displayed-pattern identity.

Construction rejects:

- empty or excessive plans;
- duplicate or non-contiguous step identities;
- unsupported characters and oversized identifiers;
- excessive per-step or aggregate delays;
- invalid role combinations;
- photometric-stereo frames that are not mapped to an exact displayed pattern.

The first workflow-specific invariants are:

- Background-Oriented Schlieren: exactly one reference and at least one disturbed
  capture;
- display photometric stereo: at least three directional-light captures, with an
  explicit pattern ID for every frame.

### Capture providers

`CaptureProvider` is the only camera-acquisition boundary visible to future
measurement orchestration. Implementations may be:

- manual upload;
- deterministic mock capture for tests;
- the versioned Photographer REST bridge.

The SPI exposes provider health and limits, redacted devices, idempotent session
creation, polling/events, checksummed asset metadata, full/ranged streaming and
cancellation. Stable failure enums and message codes cross the boundary; private
camera addresses, credentials, local file paths and raw provider exception text
do not.

The currently merged deterministic mock provider exercises the exact-once
contract and visible status surface. It is disabled by default and does not make
BOS claim `REMOTE_CAPTURE`; a plugin advertises that capability only after an
actual workflow action is wired through a selected provider.

## Plugin capabilities

Measurement plugins advertise only the actions they implement:

```text
GENERATE_CAPTURE_TARGET
IMPORT_CAPTURE_SET
REMOTE_CAPTURE
ANALYZE_CAPTURE_SET
LIVE_ANALYSIS_PREVIEW
EXPORT_EXPERIMENT_BUNDLE
```

As with existing design actions, a capability is shown only when both the backend
descriptor and a trusted local frontend handler support it. Capability strings
never become endpoint URLs, class names or dynamic imports.

A measurement plugin may additionally use ordinary typed output capabilities
such as `PREVIEW_PNG` or `EXPORT_PNG`. `GENERATE_CAPTURE_TARGET` describes the
workflow meaning; the export capability describes the concrete transport
format.

## Fresnel and Photographer

REST is the integration contract. Docker Compose is an optional deployment mode,
not a shared runtime or storage mechanism.

```text
Fresnel measurement session
        │
        │ versioned, scoped REST + idempotency keys
        ▼
Photographer capture session
        │
        ▼
Canon CCAPI / USB-PTP / mock camera
```

Fresnel owns target generation, experiment orchestration, scientific analysis,
result visualization and final experiment bundles. Photographer owns camera
selection, exclusive camera leases, shutter and live-view operations, effective
capture settings, durable source-media storage and integrity metadata.

The applications must not share a database, filesystem volume, Java classpath or
administrator password. Both user interfaces will show negotiated protocol,
last successful communication, selected camera, active session and a truthful
connection state.

Detailed implementation work is tracked in:

- #139 — durable measurement sessions and CaptureProvider adapters;
- #140 — Background-Oriented Schlieren plugin;
- #141 — display photometric-stereo plugin;
- `carstenartur/Photographer#314` — paired Photographer capture bridge.
