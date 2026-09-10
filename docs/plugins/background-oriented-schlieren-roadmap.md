# BOS implementation sequence

1. **Target generation** — deterministic random-dot target, browser preview, full-resolution PNG and canonical job parameters. This PR.
2. **Durable measurement session** — persist immutable target artifact, state transitions and capture-plan identity using the common #139 architecture.
3. **Manual capture import** — bounded reference/disturbed image uploads, metadata, checksums and validation.
4. **Provider acquisition** — optional Photographer adapter using the provider-neutral exact-pattern contract.
5. **BOS analysis** — displacement estimation, confidence/quality masks and calibrated visualization.
6. **Experiment bundle** — canonical job, target, source captures, analysis artifacts, diagnostics and provenance manifest.

Capabilities are added to the public plugin descriptor only when their corresponding slice is implemented and tested end to end.
