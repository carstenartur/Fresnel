# Fresnel job files (`.fresnel`)

Fresnel jobs are versioned, JSON-based documents that describe a design and,
optionally, the production outputs requested from it. They are the portable source
for GUI editing, automation, documentation examples, desktop file association and
reproducible production execution.

A job contains data only. It must not contain Java class names, source code,
shell commands or dynamically loadable implementation references.

## File identity

| Property | Value |
|---|---|
| File extension | `.fresnel` |
| Media type | `application/vnd.carstenartur.fresnel.job+json` |
| Format identifier | `io.github.carstenartur.fresnel.job` |
| Current envelope version | `1` |
| JSON Schema | [`schemas/fresnel-job-v1.schema.json`](schemas/fresnel-job-v1.schema.json) |

The `+json` suffix makes the representation inspectable with ordinary JSON tools
while the vendor media type distinguishes it from unrelated JSON documents.

## Minimal document

```json
{
  "$schema": "https://carstenartur.github.io/Fresnel/schemas/fresnel-job-v1.schema.json",
  "format": "io.github.carstenartur.fresnel.job",
  "formatVersion": 1,
  "plugin": {
    "id": "zone-plate",
    "parameterSchemaVersion": 1,
    "algorithmVersion": "zone-plate/1"
  },
  "parameters": {
    "apertureDiameterMm": 10.0,
    "focalLengthMm": 1000.0,
    "wavelengthNm": 550.0,
    "dpi": 1200.0,
    "targetOffsetXmm": 0.0,
    "targetOffsetYmm": 0.0,
    "maskType": "BINARY_AMPLITUDE",
    "polarity": "POSITIVE"
  }
}
```

Complete checked-in production examples are available under
[`jobs/`](jobs/), including the Zone Plate examples used by the generated plugin
documentation.

## Using job files in the graphical interface

The **Open job…** control at the top of the application accepts both current
`.fresnel` files and legacy design `.json` files. Fresnel validates and normalizes
the document through the backend, selects the editor identified by `plugin.id`,
and populates that editor only after the import succeeds.

Each design editor provides **Save job (.fresnel)**. The downloaded file contains
the current normalized parameters and is suitable for reopening in any supported
editor. Opening another job for the same plugin recreates the editor with the new
parameter state; opening a job for another plugin switches to the corresponding
editor automatically.

When a loaded job is edited and saved, Fresnel preserves its compatible
`parameterSchemaVersion`, `algorithmVersion`, `production` plan and creator
provenance. The parameter hash is deliberately not copied: the backend recomputes
it from the edited, normalized parameter object before returning the download.

When an old `{kind, version, payload}` document is opened, the interface displays
a migration notice. The original local file is not modified. Saving afterward
creates a new `.fresnel` v1 file.

Invalid, oversized, unknown-plugin and unsupported-future-version files are shown
as import errors and do not replace the current editor state.

## Stable plugin identifiers

Jobs use IDs from `PluginRegistry`, not frontend tab names or Java class names:

| Legacy `kind` | Job `plugin.id` |
|---|---|
| `single` | `zone-plate` |
| `hex` | `hex-macro-cell` |
| `foil` | `window-foil` |
| `multifocus` | `multi-focus` |
| `rgb` | `rgb-zone-plate` |
| `hologram` | `hologram` |

The importer accepts the old `{kind, version, payload}` envelope and migrates it
to the current job representation. Existing `/api/designs/save` and
`/api/designs/load` endpoints remain available for compatibility.

## Production outputs

The optional `production.outputs` array records requested artifacts without
embedding an output directory:

```json
{
  "production": {
    "outputs": [
      {
        "id": "print-sheet",
        "format": "pdf",
        "filename": "zone-plate.pdf",
        "sheet": "A4",
        "printScale": 1.0
      }
    ]
  }
}
```

Each format is checked against the capabilities advertised by the selected
plugin. Filenames must be portable basenames; absolute paths, path separators and
parent-directory traversal are rejected. If a production plan is present, it
must contain at least one output. Missing output IDs, filenames and PDF defaults
are normalized deterministically.

`FresnelJobExecutor` executes this normalized production plan independently of
HTTP, browser state and test-only switches. It writes only through an injected
output sink, so a documentation command, application endpoint or future batch
runner chooses the destination without placing a local output directory in the
portable job.

## HTTP round-trip and production execution

The canonical endpoints are:

```text
POST /api/designs/job/save
POST /api/designs/job/load
POST /api/designs/job/execute/{outputId}
```

All consume the dedicated media type and also accept `application/json`. The load
endpoint accepts either a current job or a legacy design document. Save and load
return a normalized v1 job with resolved plugin defaults and a stable
`provenance.parameterSha256` calculated from normalized parameter JSON. The save
endpoint additionally returns a download filename ending in `.fresnel`.

The execution endpoint runs exactly one output already declared by `outputId` in
the submitted production plan. The URL cannot introduce a new format, filename or
command. Its response uses the declared media type and filename and includes
`X-Fresnel-Normalized-SHA256` for the generated artifact.

Normalize a job:

```bash
curl \
  -H 'Content-Type: application/vnd.carstenartur.fresnel.job+json' \
  -H 'Accept: application/vnd.carstenartur.fresnel.job+json' \
  --data-binary @docs/jobs/zone-plate/on-axis.fresnel \
  http://localhost:8080/api/designs/job/load
```

Execute its declared documentation preview:

```bash
curl \
  -H 'Content-Type: application/vnd.carstenartur.fresnel.job+json' \
  --data-binary @docs/jobs/zone-plate/on-axis.fresnel \
  -o on-axis.png \
  http://localhost:8080/api/designs/job/execute/documentation-preview
```

## Documentation and CLI execution

The non-test documentation command consumes the same public jobs and executor:

```bash
# Render or verify all discovered production jobs.
bash packaging/docs-jobs.sh render-all docs/jobs docs/assets/plugins
bash packaging/docs-jobs.sh verify-all docs/jobs docs/assets/plugins

# Generate or verify the machine-readable example manifest.
bash packaging/docs-jobs.sh manifest \
  docs/jobs docs/assets/plugins docs/generated/example-manifest.json
bash packaging/docs-jobs.sh verify-manifest \
  docs/jobs docs/assets/plugins docs/generated/example-manifest.json

# Inspect discovery or render a schema-derived parameter table.
bash packaging/docs-jobs.sh list docs/jobs
bash packaging/docs-jobs.sh table docs/jobs/zone-plate/on-axis.fresnel
```

The checked-in manifest at
[`generated/example-manifest.json`](generated/example-manifest.json) records the
job path, plugin ID, all compatibility versions, normalized parameter hash and
format-aware metadata for every migrated documentation artifact.

## Versioning

Three versions have separate meanings:

- `formatVersion` versions the outer job envelope.
- `parameterSchemaVersion` versions the selected plugin's parameter object.
- `algorithmVersion` identifies intentional numerical/rendering behavior.

All three compatibility fields are explicit in a v1 job. A missing plugin schema
or algorithm version is rejected rather than silently guessed. Legacy design
JSON is the exception: its migration supplies explicit v1 compatibility values.

A file using a newer unsupported envelope or parameter schema is rejected rather
than partially interpreted. Future migrations should be explicit and covered by
fixtures.

## Reproducibility hashes

The importer computes SHA-256 over canonical normalized parameters. Object key
order and equivalent number spellings such as `10` and `10.0` do not affect this
parameter hash.

Artifact hashes are format-aware:

- PNG hashes cover decoded ARGB pixels, dimensions and intended physical DPI, not
  compressed container bytes;
- SVG, DXF, Gerber and other text formats normalize line endings before hashing;
- opaque binary formats currently use a namespaced byte hash;
- PDF metadata is recorded, but future semantic PDF normalization may replace its
  current binary comparison policy.

This separation means a job's parameter identity remains stable independently of
its outputs, while documentation drift checks compare the properties that are
scientifically or visually significant for each artifact format.

## Security model

Imported jobs are untrusted input:

- input is limited to 1 MiB before JSON parsing;
- the plugin ID must exist in `PluginRegistry`;
- unknown v1 envelope, plugin, production and parameter fields are rejected;
- plugin parameters are converted to and validated as the corresponding backend
  request type;
- requested output formats must be supported by the plugin;
- output filenames cannot contain paths;
- directory sinks independently verify that resolved targets remain inside the
  caller-selected output root;
- application execution can select only an output ID already declared by the job;
- no external URL is fetched during import or documentation execution;
- no class, script or command is resolved from document data.

Large embedded assets will require a separately bounded asset/container design.
They should not be added as unbounded base64 fields to the general envelope.
