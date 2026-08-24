# Plugin Documentation

Fresnel is structured around **trusted plugin-style modules**. Each plugin is an
independent unit with parameter model, versioned schema metadata, trusted editor
extensions and either a deterministic renderer or a durable measurement
workflow. Plugins are classified explicitly as:

```text
DESIGN       deterministic optical-element rendering and export
MEASUREMENT  target generation, capture acquisition and image analysis
```

The shared measurement lifecycle and provider boundary are documented in
[Measurement plugin architecture](measurement-plugins.md). Measurement plugins
remain compiled, reviewed implementations; schema data never names code to load.

Portable, reproducible design and production requests use the versioned
[`.fresnel` job format](fresnel-job-format.md). Job files identify workflows by
stable plugin ID and can be migrated from the former generic design JSON
envelope.

Versioned parameter and presentation metadata is described in
[Plugin parameter and UI schemas](plugin-schemas.md). The same contract is
available through `GET /api/plugins/{pluginId}/schema` and is consumed by the
common React form, job validation and future documentation tooling.

Stable plugin IDs are also the primary UI routes, for example
`/plugins/zone-plate`. The ordered `GET /api/plugins` response determines plugin
navigation; former frontend mode aliases are no longer part of the public API.

The table below lists the currently available plugins. The seven established
optical renderers are `DESIGN` plugins. Background-Oriented Schlieren is the
first `MEASUREMENT` plugin and currently advertises only its implemented,
deterministic target-generation slice; capture import and analysis remain
separate future capabilities.

| Plugin | Kind | Trusted implementation | Frontend integration | Description |
|--------|------|------------------------|----------------------|-------------|
| [Zone Plate](plugins/zone-plate.md) | `DESIGN` | `ZonePlateRenderer` | Schema form + analysis extensions | Single Fresnel zone plate — binary amplitude or greyscale phase |
| [Variable-Line Grating](plugins/variable-line-grating.md) | `DESIGN` | `VariableLineGratingRenderer` | Schema form + calibration extensions | Orientation-selectable printer calibration grating with native one-bit PCL export |
| [Hex Macro Cell](plugins/hex-macro-cell.md) | `DESIGN` | `HexMacroCellRenderer` | Schema editor | Hexagonal array of sub-zone-plates focusing to a common image point |
| [Window Foil](plugins/window-foil.md) | `DESIGN` | `WindowFoilRenderer` | Schema form + cell-layout widget | Rectangular sheet tiled with hex macro cells |
| [Multi-Focus](plugins/multi-focus.md) | `DESIGN` | `MultiFocusRenderer` | Schema form + focus-point widget | Aperture divided among multiple focal targets |
| [RGB Zone Plate](plugins/rgb-zone-plate.md) | `DESIGN` | `RgbZonePlateRenderer` | Schema editor | Zone plate rendered at three wavelengths and composited into one RGB image |
| [Hologram](plugins/hologram.md) | `DESIGN` | `HologramSynthesizer` | Schema form + image/reconstruction extensions | Computer-generated hologram via the Gerchberg–Saxton algorithm |
| [Background-Oriented Schlieren](plugins/background-oriented-schlieren.md) | `MEASUREMENT` | `BosTargetGenerator` | Schema form + target preview/manifest | Deterministic random-dot target with bounded PNG export and reproduction evidence |

## Plugin structure

Each plugin consists of:

1. **Parameter record** (`optics-core` or `measurement-core`) — immutable value
   object carrying all inputs; validated in the compact constructor.
2. **Trusted implementation** — a pure renderer/synthesizer for `DESIGN`, or a
   measurement workflow built on the framework-free `measurement-core` contracts.
3. **Parameter schema** (`optics-core/src/main/resources/fresnel/plugins/`) —
   versioned Draft 2020-12 data contract with complete defaults, units and
   structural constraints.
4. **UI schema** — ordered groups, standard widgets and trusted extension IDs,
   kept separate from data validation.
5. **Common React editor** (`frontend/src/schema/`) — `PluginEditorShell` and
   `SchemaForm` provide one accessible lifecycle for schema-backed plugins.
   Complex interactions are resolved only through a compile-time registry of
   trusted widgets and extensions.
6. **Capability-driven actions** — `PluginActionBar` combines backend-advertised
   capabilities with typed local API handlers; capability names never become
   endpoint URLs dynamically.
7. **Unit and integration tests** — schema/DTO/default drift, stable routes,
   `.fresnel` round trips, rendering and numerical behavior.
8. **Documentation examples** — checked-in `.fresnel` jobs are the source for
   generated design assets; measurement manifests preserve target identity and
   later experiment evidence.

## Shared validation model

Deterministic, plugin-independent design validation is modeled in `optics-core`
with:

- `DesignValidationReport`
- `ValidationMetric`
- `ValidationFinding`
- `ValidationSeverity`
- `ValidationAssumption`

Validation layers are represented by `ValidationLayer`:

- analytical optics checks
- numerical / propagation-derived checks
- manufacturing and printability checks
- experimental validation hooks

The first form-based experimental measurement workflow is documented in
[experimental-validation.md](experimental-validation.md). It captures the
print setup, illumination conditions, measured focus, evidence references and a
computed theory-versus-experiment comparison for export as JSON or Markdown.

For practical, step-by-step print/measure workflows, see the
[experiments handbook](experiments/first-zone-plate.md).

Each design plugin exposes a report through:

`POST /api/designs/{pluginId}/validation`

Plugin authors should implement a report factory in `DesignValidationReports`
for new design plugins. If a layer cannot be computed yet, return an explicit
informational finding (not silently omitted) so downstream UIs can still render
the layer consistently. Measurement plugins use workflow-specific validation;
the BOS target slice currently performs schema, resource, geometry and
reproducibility validation rather than pretending to have a displacement result.

## Regenerating all documentation images

```bash
mvn -pl optics-core test -Dtest=PluginDocImagesTest -Dfresnel.docs=generate
```

Checked-in `.fresnel` jobs are the source of truth for current design-plugin
examples; the test harness consumes them and verifies deterministic output.
Generated images in `docs/assets/plugins/` remain committed to the repository.
Measurement targets additionally publish a versioned manifest and semantic hash.
