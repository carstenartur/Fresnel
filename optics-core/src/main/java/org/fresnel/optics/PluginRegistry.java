package org.fresnel.optics;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Static registry of all Fresnel plugins.
 *
 * <p>This is the single machine-readable source of truth for plugin metadata.
 * It replaces duplicated lists scattered across Java code, TypeScript UI
 * definitions and documentation.</p>
 *
 * <h2>Adding a new plugin</h2>
 * <ol>
 *   <li>Create the parameter record and renderer in {@code optics-core}.</li>
 *   <li>Add versioned parameter and UI schemas under
 *       {@code src/main/resources/fresnel/plugins/&lt;id&gt;/}.</li>
 *   <li>Add a {@link PluginDescriptor} constant below and register it in
 *       {@link #ALL}.</li>
 * </ol>
 */
public final class PluginRegistry {

    /** Single Fresnel zone plate — binary amplitude or greyscale phase. */
    public static final PluginDescriptor ZONE_PLATE = new PluginDescriptor(
            "zone-plate",
            "Zone Plate",
            "Single Fresnel zone plate — binary amplitude or greyscale phase",
            "ZonePlateRenderer",
            "SingleZonePlateParameters",
            "docs/plugins/zone-plate.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.EXPORT_SVG,
                    PluginCapability.EXPORT_PDF,
                    PluginCapability.EXPORT_DXF,
                    PluginCapability.EXPORT_GERBER,
                    PluginCapability.PREVIEW_PNG,
                    PluginCapability.PROPAGATION_PREVIEW,
                    PluginCapability.PRINTABILITY_ANALYSIS,
                    PluginCapability.OPTICAL_QUALITY_REPORT
            ),
            Set.of(PropagationMode.FRESNEL_TF, PropagationMode.FRAUNHOFER),
            schema("zone-plate", PluginEditorMode.SCHEMA_WITH_EXTENSIONS)
    );

    /** Zone plate rendered at three wavelengths and composited into one RGB image. */
    public static final PluginDescriptor RGB_ZONE_PLATE = new PluginDescriptor(
            "rgb-zone-plate",
            "RGB Zone Plate",
            "Zone plate rendered at three wavelengths and composited into one RGB image",
            "RgbZonePlateRenderer",
            "RgbZonePlateParameters",
            "docs/plugins/rgb-zone-plate.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of(),
            schema("rgb-zone-plate", PluginEditorMode.SCHEMA)
    );

    /** Aperture divided among multiple focal targets. */
    public static final PluginDescriptor MULTI_FOCUS = new PluginDescriptor(
            "multi-focus",
            "Multi-Focus",
            "Aperture divided among multiple focal targets",
            "MultiFocusRenderer",
            "MultiFocusParameters",
            "docs/plugins/multi-focus.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of(),
            schema("multi-focus", PluginEditorMode.SCHEMA_WITH_EXTENSIONS)
    );

    /** Hexagonal array of sub-zone-plates focusing to a common image point. */
    public static final PluginDescriptor HEX_MACRO_CELL = new PluginDescriptor(
            "hex-macro-cell",
            "Hex Macro Cell",
            "Hexagonal array of sub-zone-plates focusing to a common image point",
            "HexMacroCellRenderer",
            "HexMacroCellParameters",
            "docs/plugins/hex-macro-cell.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.EXPORT_PDF,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of(),
            schema("hex-macro-cell", PluginEditorMode.SCHEMA)
    );

    /** Rectangular sheet tiled with hex macro cells. */
    public static final PluginDescriptor WINDOW_FOIL = new PluginDescriptor(
            "window-foil",
            "Window Foil",
            "Rectangular sheet tiled with hex macro cells",
            "WindowFoilRenderer",
            "WindowFoilParameters",
            "docs/plugins/window-foil.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.EXPORT_PDF,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of(),
            schema("window-foil", PluginEditorMode.SCHEMA_WITH_EXTENSIONS)
    );

    /** Computer-generated hologram via the Gerchberg–Saxton algorithm. */
    public static final PluginDescriptor HOLOGRAM = new PluginDescriptor(
            "hologram",
            "Hologram (GS)",
            "Computer-generated hologram via the Gerchberg–Saxton algorithm",
            "HologramSynthesizer",
            "HologramParameters",
            "docs/plugins/hologram.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.EXPORT_STL,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of(),
            schema("hologram", PluginEditorMode.SCHEMA_WITH_EXTENSIONS)
    );

    /**
     * Immutable public plugin order. The frontend consumes this order directly
     * from {@code GET /api/plugins}; no second tab/mode ordering is maintained.
     */
    public static final List<PluginDescriptor> ALL = List.of(
            ZONE_PLATE,
            HEX_MACRO_CELL,
            WINDOW_FOIL,
            MULTI_FOCUS,
            RGB_ZONE_PLATE,
            HOLOGRAM
    );

    private static final Map<String, PluginDescriptor> BY_ID =
            ALL.stream().collect(Collectors.toUnmodifiableMap(
                    PluginDescriptor::id, Function.identity()));

    private PluginRegistry() {}

    private static PluginSchemaDescriptor schema(String pluginId, PluginEditorMode editorMode) {
        String root = "fresnel/plugins/" + pluginId + "/";
        return new PluginSchemaDescriptor(
                1,
                root + "parameters-v1.schema.json",
                root + "ui-v1.json",
                editorMode);
    }

    /** Returns the plugin with the given id, or empty when it is unknown. */
    public static Optional<PluginDescriptor> findById(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Returns the plugin with the given id or throws for an unknown public id. */
    public static PluginDescriptor requireById(String id) {
        PluginDescriptor descriptor = BY_ID.get(id);
        if (descriptor == null) throw new IllegalArgumentException("unknown plugin id: " + id);
        return descriptor;
    }

    /** Returns {@code true} if a plugin with the given id is registered. */
    public static boolean hasPlugin(String id) {
        return BY_ID.containsKey(id);
    }

    /** Returns all plugins advertising the given capability in registry order. */
    public static List<PluginDescriptor> withCapability(PluginCapability capability) {
        return ALL.stream()
                .filter(descriptor -> descriptor.supports(capability))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Verifies unique plugin IDs and unique schema resources. Called from tests to
     * turn metadata drift into an immediate build failure.
     */
    static void verifyIntegrity() {
        long distinctIds = ALL.stream().map(PluginDescriptor::id).distinct().count();
        if (distinctIds != ALL.size()) {
            throw new IllegalStateException("Duplicate plugin ids detected in PluginRegistry");
        }
        long distinctParameterSchemas = ALL.stream()
                .map(descriptor -> descriptor.schema().parameterSchemaResource())
                .distinct().count();
        if (distinctParameterSchemas != ALL.size()) {
            throw new IllegalStateException("Duplicate parameter schema resources detected");
        }
        long distinctUiSchemas = ALL.stream()
                .map(descriptor -> descriptor.schema().uiSchemaResource())
                .distinct().count();
        if (distinctUiSchemas != ALL.size()) {
            throw new IllegalStateException("Duplicate UI schema resources detected");
        }
        for (PluginDescriptor descriptor : ALL) {
            if (!BY_ID.containsKey(descriptor.id())) {
                throw new IllegalStateException(
                        "BY_ID index is out of sync for id: " + descriptor.id());
            }
        }
    }
}
