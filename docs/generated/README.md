# Generated documentation metadata

`example-manifest.json` is generated from the public `.fresnel` jobs under
`docs/jobs/` and the normalized artifacts under `docs/assets/plugins/`.

The manifest is machine-owned. It records:

- stable example and plugin IDs,
- job, parameter-schema and algorithm versions,
- the normalized parameter SHA-256,
- every claimed artifact path and media type,
- checked-in byte size,
- raster dimensions and intended DPI where applicable,
- the format-aware normalized artifact SHA-256.

Regenerate it after intentionally changing a documentation job or artifact:

```bash
bash packaging/docs-jobs.sh manifest \
  docs/jobs \
  docs/assets/plugins \
  docs/generated/example-manifest.json
```

Verify jobs, artifacts and the checked-in manifest without modifying the checkout:

```bash
bash packaging/docs-jobs.sh verify-manifest \
  docs/jobs \
  docs/assets/plugins \
  docs/generated/example-manifest.json
```

CI runs the same verification command. Generated parameter tables embedded in
plugin Markdown are independently drift-checked against the normalized jobs and
versioned plugin schemas.
