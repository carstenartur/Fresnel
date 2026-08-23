# Fresnel Measurement Core

`measurement-core` is the framework-free boundary for camera-assisted optical
measurement workflows. It deliberately has no Spring, persistence, HTTP client,
frontend or camera-vendor dependency.

## Contracts

- `MeasurementSessionState` defines the durable Fresnel experiment lifecycle.
- `CapturePlan` is a bounded, declarative and immutable sequence of captures.
- `CaptureProvider` abstracts manual/mock capture and the future Photographer REST
  bridge.
- `CaptureProviderException` carries stable failure classifications and sanitized
  message codes rather than provider exception text.

The first supported workflow contracts are:

- `BACKGROUND_ORIENTED_SCHLIEREN` — exactly one reference followed by one or more
  disturbed captures;
- `DISPLAY_PHOTOMETRIC_STEREO` — at least three mapped directional-light captures,
  optionally accompanied by dark and flat frames.

Plans reject commands, callback URLs and paths by construction. Step counts,
settle delays, identifiers and ordered sequence indexes are bounded before a
provider can contact a camera. A displayed photometric-stereo pattern is bound by
both a stable pattern ID and a lowercase SHA-256 value.

`CaptureProvider.triggerStep(...)` is an exact-once contract. The caller supplies
the observed pattern ID/hash and an idempotency key only after the display has
acknowledged the immutable pattern. A provider must return the original committed
capture when the same normalized request is retried and reject key reuse with
changed input.

Provider health uses shared states ranging from `NOT_CONFIGURED` and `PAIRING` to
`CONNECTED`, `DEGRADED`, `DISCONNECTED`, `INCOMPATIBLE`,
`AUTHENTICATION_FAILED` and `REVOKED`. Asset metadata uses portable basenames,
explicit media types, byte lengths and lowercase SHA-256 values; full and bounded
range reads share the same verified asset identity.

## Build

```bash
mvn -pl measurement-core test
mvn -pl measurement-core verify
```

Provider implementations and durable session persistence belong in the backend.
Optical target generation and image-analysis algorithms build on this module
without depending on Photographer code.
