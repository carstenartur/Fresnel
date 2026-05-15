# Plugin Documentation

Fresnel is structured around **plugin-style modules**: each diffractive element
type is an independent, self-contained unit (Java renderer + parameter record +
React panel) that can be added or replaced without touching the core pipeline.

The table below lists all currently available plugins.

| Plugin | Java renderer | Frontend panel | Description |
|--------|--------------|----------------|-------------|
| [Zone Plate](plugins/zone-plate.md) | `ZonePlateRenderer` | `SingleZonePlatePanel` | Single Fresnel zone plate — binary amplitude or greyscale phase |
| [RGB Zone Plate](plugins/rgb-zone-plate.md) | `RgbZonePlateRenderer` | `RgbPanel` | Zone plate rendered at three wavelengths and composited into one RGB image |
| [Multi-Focus](plugins/multi-focus.md) | `MultiFocusRenderer` | `MultiFocusPanel` | Aperture divided among multiple focal targets |
| [Hex Macro Cell](plugins/hex-macro-cell.md) | `HexMacroCellRenderer` | `HexMacroCellPanel` | Hexagonal array of sub-zone-plates focusing to a common image point |
| [Window Foil](plugins/window-foil.md) | `WindowFoilRenderer` | `WindowFoilPanel` | Rectangular sheet tiled with hex macro cells |
| [Hologram](plugins/hologram.md) | `HologramSynthesizer` | `HologramPanel` | Computer-generated hologram via the Gerchberg–Saxton algorithm |

## Plugin structure

Each plugin consists of:

1. **Parameter record** (`optics-core`) — immutable value object carrying all
   inputs; validated in the compact constructor.
2. **Renderer** (`optics-core`) — pure static `render(Parameters)` method that
   returns a `RenderResult` (image + physical pixel size).
3. **React panel** (`frontend/src/modes/`) — parameter form, live validation,
   preview and export buttons.
4. **Unit tests** (`optics-core/src/test/`) — structural and numerical tests.
5. **Doc-image test** (`PluginDocImagesTest`) — renders example images and writes
   them to `docs/assets/plugins/<plugin>/`.

## Regenerating all documentation images

```bash
mvn -pl optics-core test -Dtest=PluginDocImagesTest -Dfresnel.docs=generate
```

The images in `docs/assets/plugins/` are committed to the repository.
Run the command above and commit the changed image files whenever the rendering
logic changes.
