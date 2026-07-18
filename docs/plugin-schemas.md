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

- the stable `id`, display name and documentation URL,
- `parameterSchemaVersion`,
- `editorMode`,
- `schemaUrl`,
- capabilities and propagation modes.

Java renderer/parameter class names, classpath resource names and former
frontend mode aliases remain internal and are not part of the HTTP contract.

## Stable plugin-ID routes

The stable plugin ID is now the only navigation and job-file identity:

```text
/plugins/zone-plate
/plugins/hex-macro-cell
/plugins/window-foil
/plugins/multi-focus
/plugins/rgb-zone-plate
/plugins/hologram
```

The ordered response from `GET /api/plugins` determines the design-navigation
order. React keeps only a compile-time registry from trusted plugin IDs to local
components. A schema or server response can therefore select a registered
plugin, but cannot name a module to import or arbitrary code to execute.

Opening a `.fresnel` job—including a native desktop/file-association hand-off—
navigates directly from `job.plugin.id`; no `kind -> tab -> mode -> component`
translation is involved. If the backend ever advertises an ID without a trusted
local component, the UI shows an explicit integration error rather than silently
hiding that plugin.

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
- the standard `readOnly` annotation,
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
keywords therefore require an explicit compatibility and implementation change
rather than being silently ignored.

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
- read-only field presentation,
- bounded visibility conditions,
- trusted advanced editor extensions.

Every addressable parameter field must occur in exactly one group. Unknown,
duplicate or omitted paths fail startup validation. Unknown fields in the UI
schema, groups, widgets or conditions also fail startup; executable-looking
metadata cannot be smuggled through an ignored property.

### Safe visibility conditions

A group or individual widget may declare `visibleWhen`:

```json
{
  "visibleWhen": {
    "path": "outputType",
    "equals": "GREYSCALE_PHASE"
  }
}
```

The condition contains a stable parameter path and exactly one operator:

```text
equals
notEquals
oneOf
```

The backend verifies that:

- the path addresses a published parameter field,
- exactly one operator is present,
- comparison values match the parameter type,
- enum comparisons use declared enum values,
- `oneOf` is non-empty and has no duplicates,
- no extra condition properties are present.

There is intentionally no expression language, JavaScript, template evaluation
or dynamic property lookup. Hiding a field/group never deletes its value from
the public parameter object. Showing it again restores the same canonical value.

### Trusted standard widgets

The standard registry contains:

```text
number-with-presets
select
radio
read-only
```

`radio` is limited to non-empty string enums. `number-with-presets` is limited to
numeric fields and numeric preset values. `read-only` displays a current/default
value without offering a mutation path.

Complex, application-owned widgets are selected through these additional IDs:

```text
focus-point-list
window-cell-layout
hologram-target-image
```

The UI schema may select only these symbolic IDs. React resolves complex widgets
from a compile-time registry. Remote modules, dynamic imports derived from
schema data and arbitrary component names are forbidden.

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

Extensions are application-owned components. The schema controls availability
only; it cannot provide code.

## Common React editor shell

All six current plugins use `PluginEditorShell`. The shell owns:

1. loading `GET /api/plugins/{pluginId}/schema`,
2. loading and error presentation,
3. initializing schema defaults for a new design,
4. rendering `SchemaForm`,
5. resolving only explicitly supplied trusted widgets,
6. debouncing canonical structural validation,
7. rejecting stale validation results by parameter fingerprint,
8. checking visible/incomplete input state before exposing a valid result,
9. running the plugin domain-validation report only for normalized parameters,
10. handing schema, normalized state and domain report to typed actions and
    trusted extensions.

The plugin panels retain only renderer-specific operations and trusted advanced
state. Standard labels, groups, defaults, units, enum choices, numeric
constraints and validation timing are no longer duplicated in each panel.

`PluginActionBar` renders an action only when both conditions are true:

- the backend descriptor advertises the corresponding `PluginCapability`, and
- the trusted frontend implementation provides the typed action handler.

Endpoint URLs are selected by typed API helpers, never derived dynamically from
capability strings.

### Action gates

The common lifecycle distinguishes three states:

1. **Incomplete/structurally invalid** — preview, save and production actions are
   disabled. Incomplete numeric text remains visible and is never converted to
   `0`, `NaN` or a stale value.
2. **Structurally valid draft** — the normalized parameter object may be
   previewed and saved as `.fresnel`. This allows reproducible investigation of
   designs that are not yet fabrication-ready.
3. **Domain-valid production design** — fabrication exports are enabled only
   after the authoritative plugin report accepts the normalized object. The Zone
   Plate additionally requires its detailed optics/printability validation.

Thus a physically undersampled Zone Plate can still be saved and visually
inspected, but PNG/SVG/PDF/DXF/Gerber/calibration production outputs remain
blocked until its fabrication constraints pass.

## Canonical structural validation API

Editors submit their public parameter object directly to:

```text
POST /api/plugins/{pluginId}/parameters/validate
Content-Type: application/json
```

The body is the parameter object itself, not another UI-specific envelope. A
successful response includes the normalized object used by `.fresnel` import:

```json
{
  "pluginId": "zone-plate",
  "parameterSchemaVersion": 1,
  "valid": true,
  "normalizedParameters": {
    "apertureDiameterMm": 10.0,
    "focalLengthMm": 1000.0,
    "wavelengthNm": 550.0,
    "dpi": 1200.0,
    "targetOffsetXmm": 0.0,
    "targetOffsetYmm": 0.0,
    "maskType": "BINARY_AMPLITUDE",
    "polarity": "POSITIVE"
  },
  "errors": []
}
```

Invalid data returns HTTP 200 with `valid: false` and stable machine-readable
paths, allowing the form to remain an ordinary validation workflow rather than
an exceptional transport failure:

```json
{
  "pluginId": "multi-focus",
  "parameterSchemaVersion": 1,
  "valid": false,
  "errors": [
    {
      "path": "focusPoints[0].zMm",
      "code": "CONSTRAINT_VIOLATION",
      "message": "must be greater than 0"
    }
  ]
}
```

Unknown plugin IDs still return 404. Unknown parameter properties are reported
with `UNKNOWN_FIELD`; no class name, command or renderer selector can enter the
canonical parameter object.

The endpoint constructs an in-memory canonical job and delegates to
`FresnelJobService`. GUI validation, file import, desktop-open handling and
future CLI execution therefore share DTO conversion, defaults, nested bean
validation and unknown-field rules.

## Live domain validation

After structural normalization, `PluginEditorShell` debounces and submits only
the normalized object to:

```text
POST /api/designs/{pluginId}/validation
```

The returned `DesignValidationReport` remains the authoritative source for
optical, numerical, manufacturing and experimental findings. JSON Schema does
not attempt to duplicate those scientific/domain rules. Each editor displays the
same report shape and uses `report.valid` when deciding whether a fabrication
capability may run.

Plugins may disable automatic domain validation while a prerequisite local asset
is absent. The Hologram editor does this until a target image has been selected;
no empty asset is sent to synthesis or validation endpoints.

## Editor modes

`PluginEditorMode` documents the intended integration level:

- `SCHEMA` — ordinary controls can be rendered entirely by the common form,
- `SCHEMA_WITH_EXTENSIONS` — standard controls are generated and trusted
  advanced panels extend the editor,
- `CUSTOM` — a plugin temporarily requires a fully custom trusted editor.

A custom mode is an explicit migration state, not permission for schema data to
load executable code. All current Fresnel plugins use either `SCHEMA` or
`SCHEMA_WITH_EXTENSIONS`.

## Validation layers

The generic UI keeps three validation concerns separate:

1. **UI editing state** — incomplete numeric text is shown locally and never
   silently coerced to zero or an earlier value.
2. **Structural normalization** — parameter JSON is checked through
   `POST /api/plugins/{pluginId}/parameters/validate`, which delegates to the same
   backend importer used for `.fresnel` jobs.
3. **Domain validation** — normalized parameters are checked through the shared
   plugin report endpoint; specialized optical metrics remain plugin extensions.

Only the public parameter object is stored in editor state. Preview, production
and `.fresnel` saving consume the canonically normalized form of that same
object—there is no second hidden UI model.

## Versioning and `.fresnel` jobs

`parameterSchemaVersion` is the public compatibility version used by the
`plugin.parameterSchemaVersion` field in a `.fresnel` job. It is independent of:

- the outer job `formatVersion`,
- the plugin `algorithmVersion`,
- the UI schema layout version.

Changing labels, grouping, help text, widgets or visibility normally does not
require a parameter schema version change. Renaming/removing fields, changing
their meaning or making previously valid parameter data invalid requires an
explicit migration and usually a new parameter schema version.

## Adding or changing a plugin

1. Add or update the backend request/parameter model.
2. Create a versioned parameter schema with complete defaults.
3. Create the separate UI schema.
4. Register both resources and the stable plugin ID in `PluginRegistry`.
5. Register a trusted local editor component for that ID.
6. Use a standard widget where possible; add a trusted widget only when an
   ordinary control cannot represent the data.
7. Use only the bounded condition model for visibility; never add expressions.
8. Supply typed capability action handlers; never infer endpoint URLs from names.
9. Update schema/DTO/default/enum, parameter-validation, condition and
   stable-route tests.
10. Verify save/open round trips through the `.fresnel` job API.
11. Add or update plugin documentation examples.

The application validates all registered schema resources at startup. A plugin
with missing or inconsistent metadata is therefore a build/startup error, not a
partially functioning editor.
