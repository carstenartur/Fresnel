# Fresnel job files (`.fresnel`)

Fresnel jobs are versioned, JSON-based documents that describe a design and,
optionally, the production outputs requested from it. They are intended to be the
portable source for GUI editing, automation, documentation examples and later
operating-system file association.

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

A complete checked-in example is available at
[`jobs/zone-plate/on-axis.fresnel`](jobs/zone-plate/on-axis.fresnel).

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
parent-directory traversal are rejected.

## HTTP round-trip

The canonical endpoints are:

```text
POST /api/designs/job/save
POST /api/designs/job/load
```

Both consume the dedicated media type and also accept `application/json`. The
load endpoint accepts either a current job or a legacy design document. Both
return a normalized v1 job with resolved Zone Plate defaults and a stable
`provenance.parameterSha256` calculated from normalized parameter JSON.

The save endpoint additionally returns a download filename ending in
`.fresnel`.

Example:

```bash
curl \
  -H 'Content-Type: application/vnd.carstenartur.fresnel.job+json' \
  -H 'Accept: application/vnd.carstenartur.fresnel.job+json' \
  --data-binary @docs/jobs/zone-plate/on-axis.fresnel \
  http://localhost:8080/api/designs/job/load
```

## Versioning

Three versions have separate meanings:

- `formatVersion` versions the outer job envelope.
- `parameterSchemaVersion` versions the selected plugin's parameter object.
- `algorithmVersion` identifies intentional numerical/rendering behavior.

A file using a newer unsupported envelope or parameter schema is rejected rather
than partially interpreted. Future migrations should be explicit and covered by
fixtures.

## Reproducibility hash

The importer computes SHA-256 over canonical normalized parameters. Object key
order and equivalent number spellings such as `10` and `10.0` do not affect the
hash.

This parameter hash is not an artifact-file hash. Generated raster and vector
outputs need format-specific normalization rules before they can be compared
reliably; that work belongs to the documentation job executor tracked in issue
#81.

## Security model

Imported jobs are untrusted input:

- input is limited to 1 MiB before JSON parsing;
- the plugin ID must exist in `PluginRegistry`;
- plugin parameters are converted to and validated as the corresponding backend
  request type;
- requested output formats must be supported by the plugin;
- output filenames cannot contain paths;
- no external URL is fetched during import;
- no class, script or command is resolved from document data.

Large embedded assets will require a separately bounded asset/container design.
They should not be added as unbounded base64 fields to the general envelope.
