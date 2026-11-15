# Photographer capture REST v1

This [OpenAPI candidate](photographer-capture-v1.openapi.yaml) covers a paired, externally triggered still-capture sequence for [Fresnel #139](https://github.com/carstenartur/Fresnel/issues/139). The [fixtures](photographer-capture-v1.fixtures.json) use synthetic profiles, rasters and source images. The rules below are part of the contract.

## Pairing, discovery and revocation

Use the administrator-configured Photographer origin. Require HTTPS off loopback unless administrators explicitly trust the container network. Reject redirects to another origin. Fresnel exchanges a short-lived, single-use, rate-limited code over this connection and stores the opaque bearer token server-side. Photographer stores a strong token hash. Keep codes, tokens and private camera details out of diagnostics and logs. Use `Cache-Control: private, no-store` on pairing and service responses.

The overlapping protocol version must include 1. A server that cannot negotiate v1 returns `409 INCOMPATIBLE` without consuming the pairing code. An accepted code returns a token once, even when the same pairing request and key are replayed. If the response is lost, revoke the orphaned pairing in Photographer and issue a fresh code.

Authorize the bearer, its scope and resource ownership before idempotency lookup, camera dispatch or conditional media reads. A missing, invalid or revoked token receives `401 AUTHENTICATION_FAILED` with a bearer challenge. A missing scope receives `403 INSUFFICIENT_SCOPE`. A resource outside the pairing receives the same 404 as a missing resource of that type. Photographer's admin revoke action immediately invalidates the token and fences new shutter dispatches. Already dispatched work may reach a safe stopping point and commit its media locally. Restore settings and release the lease on the safe stop path. Fresnel can distinguish confirmed revocation from an otherwise generic authentication failure when it has local evidence of the revocation.

## Map the device and validate the plan

| Fresnel measurement core | REST v1 |
| --- | --- |
| `CaptureProvider.Device.id` from discovery | `Camera.cameraProfileId`, copied verbatim |
| `CapturePlan.deviceId` in a create request | `CapturePlan.cameraProfileId`, copied verbatim |
| `CapturePlan.Requirements.preferredImageFormat` | `requestedSettings.preferredFormat` |
| `CapturePlan.Step.settleDelay` | `settleDelayMs`, whole milliseconds |
| `CapturePlan.Step` and `CaptureProvider.StepTrigger` pattern fields | `externalPatternId` and `externalPatternSha256` in both JSON bodies |
| Durable local experiment ID | `externalExperimentId`, used for correlation |
| Remote session ID | `sessionId`, persisted with the paired instance identity |

Keep device selections scoped to the paired `instanceId`. Reject a profile missing from that instance or unavailable to the pairing. Stable profile IDs are opaque strings. Return redacted discovery fields only.

Accept UTF-8 JSON bodies up to 128 KiB after HTTP framing. Reject content codings, duplicate keys and unknown request fields. Require unique step IDs and zero-based sequence indexes equal to their array positions. Limit each settle delay to 30 seconds and cumulative declared settle time to the smaller of 30 minutes and the advertised session duration. Enforce advertised step, asset and session limits. This slice accepts `EXTERNAL` triggers and bounded JPEG or PNG source capture. Reject any required format or lock the profile cannot provide before creating the session or contacting the camera. Return the effective settings and preserve the plan's steps. Adapter implementations can reconstruct the core effective plan from both.

## Bind each trigger to the presented condition

For a displayed pattern, compute SHA-256 over this exact byte sequence.

1. ASCII `FRESNEL-PATTERN-RGB8-V1` followed by a single zero byte.
2. Positive width and height, each written as an unsigned 32-bit big-endian integer.
3. The presented source raster in sRGB with 8-bit red, green and blue samples, left to right and top to bottom, with three bytes per pixel. Composite any transparency before hashing. Omit row padding, encoding metadata and file headers.

Hash the raster actually staged for presentation after Fresnel applies software cropping, compositing or resampling. If these operations change the planned raster, rebuild the plan before triggering. The digest identifies the staged pixels independently of file encoding, display hardware and the camera's source image. A pattern ID maps to one hash within the plan. The trigger must carry the same ID and hash as its step. A physical-only BOS condition omits both fields and still includes `presentedAt` after the operator confirms it.

Fresnel's display workflow acknowledges the raster after rendering and settling, then triggers while the condition remains in place. Fresnel keeps that condition in place through completion or a safe stop and treats loss of presentation as a reason to discard an exposure whose condition cannot be verified. Photographer compares the trigger to the immutable plan. It uses its own monotonic clock to wait the declared settle interval after accepting the trigger. `presentedAt` records Fresnel's observation without relying on synchronized clocks to shorten that interval. The [photometric-stereo workflow](https://github.com/carstenartur/Fresnel/issues/141) defines the browser acknowledgement.

## Persist the trigger before touching the camera

- Scope a mutation key to the pairing, operation and relevant resource IDs. For create, the resource is the session collection. For trigger, use both session and step IDs. Save the SHA-256 of the exact received JSON body bytes, or of the empty body for cancel. Fresnel persists the original body and key before sending. The same key with changed bytes receives `409 IDEMPOTENCY_CONFLICT`. Keep the key and result through restart, while the session is active or retained, and for at least the advertised `idempotencyRetentionSeconds` following terminal state. Fresnel stops unattended create retries once it is outside the guaranteed replay window.
- Atomically reserve the next eligible step and its key before work starts. Hold one logical camera lease for the active sequence and validate it before a state change. Record an irreversible dispatch fence before invoking the shutter. Serialize dispatch with cancellation and revocation. Use a backend capture call with automatic retries of ambiguous shutter operations disabled.
- Treat an identical pending replay as `202` and an identical committed replay as `200` with the original asset. A new key on a previously reserved or completed step receives `409 STEP_ALREADY_TRIGGERED` except when the outcome is uncertain. Persist source bytes, metadata and their completion record before exposing the asset or moving to the next step. After startup, verify completed assets and recover their original results.
- If the shutter may have fired and the committed asset cannot be recovered, mark the step and session `RECOVERY_REQUIRED`. Stop dispatches for the session. Both same-key and new-key triggers return `409 RECOVERY_REQUIRED` for an unresolved step. An identical retry of a different, already committed step can still retrieve its asset. Poll for results or request a safe stop. Operator reconciliation precedes any further capture; this slice has no remote command to reset a shutter fence.
- Bound external-trigger waits by `maximumTriggerWaitMs`. On wait timeout, pause, restore settings and release the lease. On cancel, record the stop and fence undispatched work before acknowledging it, then poll for the terminal outcome. Capture status and event cursors survive restart. Return a bounded page with only committed event IDs. The client advances to the last returned ID.

The terminal session states are `COMPLETED`, `FAILED` and `CANCELLED`. A step is completed only when it has an immutable source result. `RECOVERY_REQUIRED` can represent multiple causes and never grants permission to fire again.

## Verify the complete media representation

Use the immutable source's full length and SHA-256 from metadata. The strong ETag is the quoted lowercase checksum of the complete uncoded asset. A full GET returns `200`, length and media type. HEAD returns the same representation headers with no body. Matching `If-None-Match` returns `304` and the ETag. The server evaluates these preconditions only after authorizing access.

For resume, request one inclusive `bytes=start-end` range with `If-Range` set to the original strong ETag and request identity encoding. Append a `206` only if the ETag, content type, start offset, response length and total from `Content-Range` match expectations. The server clamps the end to the last available byte. An unsatisfiable start returns `416 RANGE_NOT_SATISFIABLE` with `Content-Range: bytes */length`. Reject malformed or unsupported ranges with `400 INVALID_REQUEST`. A stale validator yields a full `200`. Drop the local prefix before restarting from that response and confirm its metadata. Import the asset only after verifying the assembled length and checksum. Discard a transfer with any integrity mismatch. [HTTP semantics](https://www.rfc-editor.org/rfc/rfc9110.html#name-range-requests)

## Compatibility and next implementation slice

Use stable sanitized problem codes and [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html) problem objects. Return 400 for invalid request shape, 422 `INVALID_PLAN` for invalid plan relationships and 422 `PATTERN_MISMATCH` for a trigger that conflicts with a planned pattern or physical condition. Distinguish a lease conflict, an idempotency conflict and an uncertain shutter. Keep error text bounded. Clients ignore additive response fields and unknown optional capabilities. An unknown state prevents automatic progression until the client can interpret it. New required request semantics, changed state or authorization meaning, and changed media integrity rules require a new major version.

The next implementation slice pins the reviewed OpenAPI and fixtures by digest in both builds and runs them through a deterministic paired mock-camera session. Photographer admin UI and token storage, Fresnel pairing UI and adapter, SSE, online pause/resume and operator reconciliation, RAW previews, production deployment and the BOS and display photometric-stereo clients follow separately. This contract's fixtures exercise polling and Photographer-admin revocation. An endpoint for Fresnel-initiated remote revocation is a follow-up decision.
