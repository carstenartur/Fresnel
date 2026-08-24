# BOS target test matrix

| Contract | Test level |
|---|---|
| Parameter bounds and cross-field limits | optics-core unit tests |
| Same seed yields identical pixels | optics-core pixel-hash test |
| Different seed changes pixels | optics-core pixel-hash test |
| Minimum center distance | independent pairwise unit assertion |
| Infeasible density fails | optics-core unit test |
| Full dimensions and PNG response | backend controller test |
| Seed/count/fill provenance headers | backend controller test |
| Canonical job hash includes seed | backend job test |
| Graphical preview and download | Playwright browser test |
| Registry capability honesty | registry and documentation tests |
