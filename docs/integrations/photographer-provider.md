# Photographer capture provider

Enable the `photographer-rest` provider and establish its pairing from **Camera service** as described in the [deployment guide](../security/deployment.md#pairing-a-photographer-capture-service). Fresnel records the negotiated limits when an administrator pairs the service. Its status panel uses an authenticated health request and camera discovery to show current availability.

## Capture and recovery

A measurement workflow obtains `photographer-rest` from `CaptureProviderRegistry` and passes its durable local experiment ID to `createSession(plan, externalExperimentId, key)`. Use the camera profile ID from calibrated device selection on that pairing. The workflow must check its local owner before using the saved remote session. It also owns the observation that the intended physical condition or displayed raster remains in place.

The adapter stores immutable session plans, original asset identities and the exact outbound bytes for idempotent operations under `fresnel.capture.photographer.journal-directory` (the `photographer-captures` subdirectory of the Fresnel data directory by default). Keep this directory on persistent local storage, separate from Photographer. The adapter scopes these records to the origin, instance and pairing. A reconnect with the same pairing can replay the same key. When a create request is older than the one-day replay guarantee, Fresnel stops automatic recreation. Its workflow can reconcile an existing session through its recorded ID. Keep a journal while it is needed for experiment recovery.

An accepted asynchronous trigger is followed by bounded status polling. Only a committed result for the requested session, step and planned role can return to the measurement workflow. On presentation loss, cancel with one durable key and poll until the server reports its safe stopping state. A recovery-fenced step requires operator reconciliation.

`openAsset(..., FullAsset.INSTANCE)` streams the source to a temporary local file. It verifies the original strong ETag, content type, length and SHA-256 before returning a readable stream. An interrupted full transfer can resume with `Range` and `If-Range`. A matching full response replaces the temporary prefix. Close the returned stream after persisting the verified bytes in the experiment. An explicitly requested `ByteRange` validates the returned range against its pinned representation. A caller assembling separate ranges must verify the complete original digest before importing the source.

## Verification

The fixture tests read the repository's [contract and test vectors](photographer-capture-v1.md) and run a loopback HTTP service. Run them from the Fresnel repository root.

```sh
mvn -Pno-frontend -pl backend -am -Dtest=PhotographerCaptureProviderTest,PhotographerPairingTest,PhotographerPairingAuthorizationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`PhotographerInteropTest` exercises both running products independently of their build classpaths. Start a dedicated Photographer instance with the Fresnel bridge enabled and a mock camera configured. Set `PHOTOGRAPHER_TEST_ORIGIN` and provide a newly issued one-time code in the environment variable `PHOTOGRAPHER_TEST_PAIR_CODE`. Run the dedicated test.

```sh
mvn -Pno-frontend -pl backend -am -Dtest=PhotographerInteropTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The test captures a BOS reference/disturbed pair and uses the canonical photometric-stereo raster commitments for three light steps. It checks a byte-range reconstruction against the original SHA-256, verifies the full-source stream and reconstructs the Fresnel client from its persisted state. To exercise a real Photographer restart and administrative revocation, set `PHOTOGRAPHER_TEST_CHECKPOINTS` to an empty directory. When `restart.request` appears, restart Photographer with its original data, wait for readiness, and create `restart.done`. When `revoke.request` appears, revoke the pairing ID in that file in Photographer and create `revoke.done`. The test checks fenced recovery, safe replay, cancellation and the resulting authentication state. Codes and bearer tokens are never written to the checkpoint directory.
