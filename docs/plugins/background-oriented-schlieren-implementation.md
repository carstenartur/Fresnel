# BOS implementation boundary

The first implementation slice deliberately stops at the immutable capture target. It does not infer a displacement field from images and it does not claim that a connected camera has captured the displayed target.

The exact target identity consists of the normalized BOS parameter object, algorithm version and generated PNG digest. A future `CapturePlan.Step` references that immutable identity. The capture provider may release the shutter only after Fresnel supplies the matching displayed-pattern evidence required by the common measurement contract.

This keeps three concerns separate:

1. `optics-core` deterministically generates and tests the target pixels.
2. the backend validates jobs, serves preview/export endpoints and later persists experiment artifacts.
3. capture-provider adapters obtain images but do not implement BOS analysis or mutate target parameters.

No target parameter is interpreted as a path, URL, class name or command. Raster allocation, dot count and placement attempts are bounded before generation.
