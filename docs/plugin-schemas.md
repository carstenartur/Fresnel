# Plugin parameter and UI schemas

Fresnel plugins expose a versioned, data-only editor contract through:

```text
GET /api/plugins/{pluginId}/schema
```

The endpoint is the shared source for graphical editors, `.fresnel` job
validation, documentation tables and future command-line tooling. It never
contains Java class names to instantiate, JavaScript module URLs or executable
expressions.

## Response structure

```json
{
  "pluginId": "zone-plate",
  "parameterSchemaVersion": 1,
  "editorMode": "SCHEMA_WITH_EXTENSIONS",
  "parameterSchema": { "...": "Draft 2020-12 JSON Schema" },
  "uiSchema": { "...": "presentation metadata" },
  "defaults": { "...": "complete initial parameter object" },
  "capabilities": ["EXPORT_PDF", "EXPORT_PNG", "PREVIEW_PNG"]
}
```

The normal `GET /api/plugins` response also publishes:

- `parameterSchemaVersion`,
- `editorMode`,
- `schemaUrl`.

Classpath resource names remain internal and are not part of the HTTP contract.

## Parameter schema

Each plugin has a Draft 2020-12 resource under:

```text
optics-core/src/main/resources/fresnel/plugins/<plugin-id>/parameters-v1.schema.json
```

The current public subset supports:

- object, array, string, number, integer and boolean values,
- `required`, `properties`, `items` and `additionalProperties`,
- numeric limits,
- string enums,
- complete root defaults,
- titles and descriptions,
- bounded `x-fresnel-*` annotations.

Every top-level property is checked against the corresponding backend request
record. Defaults must deserialize into that request type and pass Jakarta Bean
Validation. Enum values are checked against their Java enums.

### Fresnel annotations

The initial vocabulary is deliberately small:

| Keyword | Purpose |
|---|---|
| `x-fresnel-unit` | Physical/display unit such as `mm`, `nm`, `dpi` or `rad` |
| `x-fresnel-step` | Suggested numeric input increment |
| `x-fresnel-precision` | Suggested display precision |
| `x-fresnel-expensive` | Marks a value that can make preview generation expensive |
| `x-fresnel-widget` | Trusted widget identifier, never a module or class name |
| `x-fresnel-enum-labels` | Human-readable labels for enum values |
| `x-fresnel-sensitive-size` | Marks bounded data whose size needs special handling |
| `x-fresnel-power-of-two` | Declares the additional power-of-two domain rule |

An unregistered `x-fresnel-*` keyword prevents application startup. New
keywords therefore require an explicit compatibility and implementation
change rather than being silently ignored.

## UI schema

Presentation metadata is stored separately:

```text
optics-core/src/main/resources/fresnel/plugins/<plugin-id>/ui-v1.json
```

Separating the two documents keeps structural validation reusable by the
backend, jobs and CLI tools while allowing the React interface to change its
layout independently.

The current UI format supports:

- ordered field groups,
- collapsible and advanced groups,
- dotted paths for nested object fields,
- widget selection,
- numeric presets,
- trusted advanced editor extensions.

Every addressable parameter field must occur in exactly one group. Unknown,
duplicate or omitted paths fail startup validation.

### Trusted widgets

The first registry contains:

```text
number-with-presets
select
focus-point-list
window-cell-layout
hologram-target-image
```

The UI schema may select only these symbolic IDs. React resolves them from a
compile-time registry. Remote modules, dynamic imports derived from schema data
and arbitrary component names are forbidden.

### Trusted editor extensions

Advanced workflows plug into a common editor shell through these initial IDs:

```text
production-actions
validation
experiment
propagation
preview-info
reconstruction-preview
```

Extensions are application-owned components. The schema controls placement and
availability only; it cannot provide code.

## Editor modes

`PluginEditorMode` documents the intended integration level:

- `SCHEMA` — ordinary controls can be rendered entirely by the common form,
- `SCHEMA_WITH_EXTENSIONS` — standard controls are generated and trusted
  advanced panels extend the editor,
- `CUSTOM` — a plugin temporarily requires a fully custom trusted editor.

A custom mode is an explicit migration state, not permission for schema data to
load executable code.

## Versioning and `.fresnel` jobs

`parameterSchemaVersion` is the public compatibility version used by the
`plugin.parameterSchemaVersion` field in a `.fresnel` job. It is independent of:

- the outer job `formatVersion`,
- the plugin `algorithmVersion`,
- the UI schema layout version.

Changing labels, grouping or help text normally does not require a parameter
schema version change. Renaming/removing fields, changing their meaning or
making previously valid parameter data invalid requires an explicit migration
and usually a new parameter schema version.

## Adding or changing a plugin

1. Add or update the backend request/parameter model.
2. Create a versioned parameter schema with complete defaults.
3. Create the separate UI schema.
4. Register both resources in `PluginRegistry`.
5. Add a trusted widget only when an ordinary control cannot represent the data.
6. Update schema/DTO/default/enum tests.
7. Verify save/open round trips through the `.fresnel` job API.
8. Add or update plugin documentation examples.

The application validates all registered schema resources at startup. A plugin
with missing or inconsistent metadata is therefore a build/startup error, not a
partially functioning editor.
