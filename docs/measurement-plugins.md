# Measurement plugin architecture

Fresnel distinguishes two trusted plugin interaction models:

```text
DESIGN       deterministic optical-element rendering and export
MEASUREMENT  target generation, capture acquisition and image analysis
```

The distinction is published as `PluginMetadata.kind`. It does not enable
runtime code loading: implementations remain reviewed application code registered
through `PluginRegistry`, while parameter and UI schemas remain data-only.

## Measurement editors

The BOS target editor generates a deterministic dot field for native PNG or
physically sized SVG/PDF output. Its workers measure displacement in still
pairs, ordered frames and finite videos. The surface scanner generates a
physically defined display illumination sequence, reconstructs measured normals
and relative albedo, and exports relighting and relative relief.
[Background-Oriented Schlieren guide](plugins/background-oriented-schlieren.md) ·
[Display photometric stereo guide](plugins/display-photometric-stereo.md)

Both UI schemas declare the trusted `measurement-workflow` extension. Opening
either editor loads the usual schema-backed parameter form. Its workflow panel
shows the stage and the number of mapped or accepted sources. Choose a capture
source to open the corresponding controls. Manual files work locally. With a
paired Photographer, the camera controls show available devices and maintain
the remote session. **Camera service status** opens the global status panel,
where **Connection details** lists advertised device and service capabilities.

**Set up target**, **Collect sources** and **Analyze sources** focus the relevant
controls. They follow the state of the selected source. For example, a
complete camera sequence is ready for analysis once its accepted images can be
opened. The workflow reports analysis complete after the worker produces its
result. A retry opens the stage that needs attention so the operator can
inspect the inputs or reconcile the camera session.

Open **Source files and provenance** to inspect source roles, original sizes and
available SHA-256 digests. Select an individual image for a preview. **Save or
reopen** opens Saved experiments for the current capture type, including the
server-backed source set, settings and checksummed bundle.
[Saved measurement sessions](measurement-sessions.md)

The canonical measurement stages are:

```text
DRAFT → TARGET_READY → CALIBRATING → READY_TO_CAPTURE → CAPTURING
      → READY_TO_ANALYZE → ANALYZING → COMPLETED
```

`FAILED` and `CANCELLED` record a failed or cancelled operation. Imported
sources can enter directly at analysis. A reloaded browser can reopen the
owner's saved sources or reconcile the paired capture with server state.

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

The deterministic mock provider exercises idempotent capture and visible
connection state. The paired Photographer adapter supports remote still-pair
capture in BOS and synchronized, externally triggered exposures in photometry.
Both use the stored, verified camera originals in their analysis paths.

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
administrator password. The [Photographer provider guide](integrations/photographer-provider.md) covers pairing, supported capture plans, durable replay and verification of original media.

Detailed implementation work is tracked in:

- #139 — durable measurement sessions and CaptureProvider adapters;
- #140 — Background-Oriented Schlieren plugin;
- #141 — display photometric-stereo plugin;
- [Photographer paired capture guide](https://github.com/carstenartur/Photographer/blob/main/docs/fresnel-capture.md) — camera service setup, session limits and recovery.

The [saved measurement sessions](measurement-sessions.md) retain manual captures and their checksummed experiment bundles. The [paired deployment example](../examples/photographer-integration/README.md) covers native applications, separate containers, a native Photographer beside a Fresnel container, and separate HTTPS hosts.
