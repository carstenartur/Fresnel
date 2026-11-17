# Photographer capture provider

Enable the `photographer-rest` provider and establish its pairing from **Camera service** as described in the [deployment guide](../security/deployment.md#pairing-a-photographer-capture-service). Fresnel records the negotiated limits when an administrator pairs the service. Its status panel uses an authenticated health request and camera discovery to show current availability.

Discovery backs off after three transient health or camera-list failures on one pairing. During the cooldown the status shows the provider as disconnected. One probe can run when the interval expires. A failed probe increases the jittered interval up to 30 seconds. Changing the pairing starts fresh. Operator capture, cancellation and reconciliation requests remain independent of discovery polling.

## Run a BOS capture

1. Open **Camera service → Background-oriented schlieren capture** and sign in with your Fresnel account. Choose an available camera and select **Start BOS capture**. Photographer holds the camera lease across both exposures.
2. Set up the undisturbed scene. Check **The undisturbed reference condition is ready** and select **Capture reference**. Wait for the reference image before changing the scene.
3. Prepare the disturbance. Check **The disturbed condition is ready** and select **Capture disturbed**. A completed experiment shows both original images.
4. Use **Download reference PNG** and **Download disturbed PNG** to save the sources. Fresnel checks each image's original media type, size and SHA-256 before importing it. To verify a downloaded copy independently, compare its digest (for example, from `sha256sum image.png`) with the image's **SHA-256** entry.

After closing the browser or restarting Fresnel, sign in with the same account and choose the experiment under **Saved captures**. A completed experiment opens its locally stored PNGs. An unfinished experiment reopens at its last saved stage. After an interrupted trigger, refresh the saved capture. Retry only if the original scene remained continuously in place. Confirm continuity in the form, or cancel if it changed. Fresnel reuses the original presentation time and operation key. If Photographer reports that it requires recovery, use its administrative controls to inspect or cancel the session before starting another experiment.

This two-step workflow records the operator's acknowledgement of a physical reference and disturbed scene. Display photometric stereo additionally requires presentation of each specified raster on the light display and confirmation of the actual presentation before the corresponding exposure. Its display controls and scientific reconstruction are separate Fresnel workflows.

## Capture and recovery

A measurement workflow obtains `photographer-rest` from `CaptureProviderRegistry` and passes its durable local experiment ID to `createSession(plan, externalExperimentId, key)`. Use the camera profile ID returned by discovery for that pairing. The workflow must check its local owner before using the saved remote session. It also owns the observation that the intended physical condition or displayed raster remains in place.

The adapter stores immutable session plans, original asset identities and the exact outbound bytes for idempotent operations under `fresnel.capture.photographer.journal-directory` (the `photographer-captures` subdirectory of the Fresnel data directory by default). Keep this directory on persistent local storage, separate from Photographer. The adapter scopes these records to the origin, instance and pairing. A reconnect with the same pairing can replay the same key. When a create request is older than the one-day replay guarantee, Fresnel stops automatic recreation. Its workflow can reconcile an existing session through its recorded ID. Keep a journal while it is needed for experiment recovery.

An accepted asynchronous trigger is followed by bounded status polling. Only a committed result for the requested session, step and planned role can return to the measurement workflow. On presentation loss, cancel with one durable key and poll until the server reports its safe stopping state. A recovery-fenced step requires operator reconciliation.

`openAsset(..., FullAsset.INSTANCE)` streams the source to a temporary local file. It verifies the original strong ETag, content type, length and SHA-256 before returning a readable stream. An interrupted full transfer can resume with `Range` and `If-Range`. A matching full response replaces the temporary prefix. Close the returned stream after persisting the verified bytes in the experiment. An explicitly requested `ByteRange` validates the returned range against its pinned representation. A caller assembling separate ranges must verify the complete original digest before importing the source.

## Verification

The fixture tests read the repository's [contract and test vectors](photographer-capture-v1.md) and run a loopback HTTP service. Run them from the Fresnel repository root.

```sh
mvn -Pno-frontend -pl backend -am -Dtest=PhotographerCaptureProviderTest,PhotographerPairingTest,PhotographerPairingAuthorizationTest,BosExperimentsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`PhotographerInteropTest` exercises both running products independently of their build classpaths. Start a dedicated Photographer instance with the Fresnel bridge enabled and a mock camera configured. Set `PHOTOGRAPHER_TEST_ORIGIN` and provide a newly issued one-time code in the environment variable `PHOTOGRAPHER_TEST_PAIR_CODE`. Run the dedicated test.

```sh
mvn -Pno-frontend -pl backend -am -Dtest=PhotographerInteropTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The test captures a BOS reference/disturbed pair and uses the canonical photometric-stereo raster commitments for three light steps. It checks a byte-range reconstruction against the original SHA-256, verifies the full-source stream and reconstructs the Fresnel client from its persisted state. To exercise a real Photographer restart and administrative revocation, set `PHOTOGRAPHER_TEST_CHECKPOINTS` to an empty directory. When `restart.request` appears, restart Photographer with its original data, wait for readiness, and create `restart.done`. When `revoke.request` appears, revoke the pairing ID in that file in Photographer and create `revoke.done`. The test checks fenced recovery, safe replay, cancellation and the resulting authentication state. Codes and bearer tokens are never written to the checkpoint directory.

With Docker available locally, `mvn -B -ntp -Dfresnel.e2e.skip=false verify` runs the public-contract camera fixture in a disposable container. The test pairs once, takes the reference frame, restarts the remote process and reconstructs the Fresnel client from its persisted data. It then replays the reference, captures the disturbed frame, checks both source digests and reads the mock camera's persisted shutter count. The standard verification command uses the same test on GitHub.

## Synchronized display photometry

The photometry capture endpoint binds the selected camera to a server-generated, immutable display sequence. Camera and service must support externally triggered stills, durable media and checksummed PNG transfer. Lock exposure and focus on the camera if the service cannot lock them. Choose a light count within the advertised session limit, including the dark and flat exposures.

A presented step has two acknowledgements. After the exact raster has settled the display client sends its hash and a unique presentation ID to `steps/{step}/trigger`. After capture, it sends `steps/{step}/accept` only if it continuously displayed that raster. The server records the original acknowledgement before triggering. It verifies the source and adds it to the private measurement archive on acceptance. A repeated trigger from the same uninterrupted presentation uses the original key. Starting a different presentation cannot accept an outstanding exposure. If the display is interrupted, cancel the capture. Already accepted exposures remain in the archive.


## Resume a saved analysis

In **Saved experiments**, sign in, select a complete source set, and choose
**Analyze saved captures**. Fresnel reopens the original inputs and settings and
records the analysis attempt under your account before the browser starts its
worker. A successful run saves its checksummed result in the same experiment.

If the browser closes, the computer restarts, or the result upload is interrupted,
open the experiment again and choose **Resume saved analysis**. Fresnel starts
a new attempt from the saved original inputs. A previous tab cannot commit a
late result over the new attempt. The worker reruns the computation from the
beginning. Acquiring the original exposures again is unnecessary.
