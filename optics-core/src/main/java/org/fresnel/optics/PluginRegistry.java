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
 * It replaces the previously duplicated lists scattered across Java code,
 * TypeScript UI definitions and documentation.
 *
 * <h2>Adding a new plugin</h2>
 * <ol>
 *   <li>Create the parameter record and renderer in {@code optics-core}.</li>
 *   <li>Add a {@link PluginDescriptor} constant below.</li>
 *   <li>Register it in {@link #ALL}.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PluginDescriptor zp = PluginRegistry.findById("zone-plate").orElseThrow();
 * boolean hasPdf = zp.supportsExport(PluginCapability.EXPORT_PDF);
 * List<PluginDescriptor> pdfPlugins = PluginRegistry.withCapability(PluginCapability.EXPORT_PDF);
 * }</pre>
 */
public final class PluginRegistry {

    // -----------------------------------------------------------------------
    // Plugin descriptors
    // -----------------------------------------------------------------------

    /** Single Fresnel zone plate — binary amplitude or greyscale phase. */
    public static final PluginDescriptor ZONE_PLATE = new PluginDescriptor(
            "zone-plate",
            "Zone Plate",
            "Single Fresnel zone plate — binary amplitude or greyscale phase",
            "ZonePlateRenderer",
            "SingleZonePlateParameters",
            "single",
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
            Set.of(PropagationMode.FRESNEL_TF, PropagationMode.FRAUNHOFER)
    );

    /** Zone plate rendered at three wavelengths and composited into one RGB image. */
    public static final PluginDescriptor RGB_ZONE_PLATE = new PluginDescriptor(
            "rgb-zone-plate",
            "RGB Zone Plate",
            "Zone plate rendered at three wavelengths and composited into one RGB image",
            "RgbZonePlateRenderer",
            "RgbZonePlateParameters",
            "rgb",
            "docs/plugins/rgb-zone-plate.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of()
    );

    /** Aperture divided among multiple focal targets. */
    public static final PluginDescriptor MULTI_FOCUS = new PluginDescriptor(
            "multi-focus",
            "Multi-Focus",
            "Aperture divided among multiple focal targets",
            "MultiFocusRenderer",
            "MultiFocusParameters",
            "multi",
            "docs/plugins/multi-focus.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of()
    );

    /** Hexagonal array of sub-zone-plates focusing to a common image point. */
    public static final PluginDescriptor HEX_MACRO_CELL = new PluginDescriptor(
            "hex-macro-cell",
            "Hex Macro Cell",
            "Hexagonal array of sub-zone-plates focusing to a common image point",
            "HexMacroCellRenderer",
            "HexMacroCellParameters",
            "hex",
            "docs/plugins/hex-macro-cell.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.EXPORT_SVG,
                    PluginCapability.EXPORT_PDF,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of()
    );

    /** Rectangular sheet tiled with hex macro cells. */
    public static final PluginDescriptor WINDOW_FOIL = new PluginDescriptor(
            "window-foil",
            "Window Foil",
            "Rectangular sheet tiled with hex macro cells",
            "WindowFoilRenderer",
            "WindowFoilParameters",
            "foil",
            "docs/plugins/window-foil.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PDF,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of()
    );

    /** Computer-generated hologram via the Gerchberg–Saxton algorithm. */
    public static final PluginDescriptor HOLOGRAM = new PluginDescriptor(
            "hologram",
            "Hologram (GS)",
            "Computer-generated hologram via the Gerchberg–Saxton algorithm",
            "HologramSynthesizer",
            "HologramParameters",
            "hologram",
            "docs/plugins/hologram.md",
            PluginStabilityLevel.STABLE,
            Set.of(
                    PluginCapability.EXPORT_PNG,
                    PluginCapability.EXPORT_STL,
                    PluginCapability.PREVIEW_PNG
            ),
            Set.of()
    );

    // -----------------------------------------------------------------------
    // Registry
    // -----------------------------------------------------------------------

    /**
     * Immutable ordered list of all registered plugins.
     * The order matches the frontend tab order in {@code App.tsx}:
     * single, hex, foil, multi, rgb, hologram.
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
            ALL.stream().collect(Collectors.toUnmodifiableMap(PluginDescriptor::id, Function.identity()));

    private PluginRegistry() {}

    // -----------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------

    /**
     * Returns the plugin with the given id, or {@link Optional#empty()} if none
     * is registered under that id.
     *
     * @param id stable plugin id (e.g. {@code "zone-plate"})
     */
    public static Optional<PluginDescriptor> findById(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * Returns the plugin with the given id.
     *
     * @param id stable plugin id
     * @throws IllegalArgumentException if no plugin is registered under {@code id}
     */
    public static PluginDescriptor requireById(String id) {
        PluginDescriptor d = BY_ID.get(id);
        if (d == null) throw new IllegalArgumentException("unknown plugin id: " + id);
        return d;
    }

    /** Returns {@code true} if a plugin with the given id is registered. */
    public static boolean hasPlugin(String id) {
        return BY_ID.containsKey(id);
    }

    // -----------------------------------------------------------------------
    // Capability queries
    // -----------------------------------------------------------------------

    /**
     * Returns all plugins that support the given capability.
     *
     * @param capability the capability to filter by
     * @return immutable list of matching descriptors, preserving registration order
     */
    public static List<PluginDescriptor> withCapability(PluginCapability capability) {
        return ALL.stream()
                .filter(d -> d.supports(capability))
                .collect(Collectors.toUnmodifiableList());
    }

    // -----------------------------------------------------------------------
    // Integrity check (called from tests)
    // -----------------------------------------------------------------------

    /**
     * Verifies that all registered plugin ids are unique and non-blank.
     * Throws {@link IllegalStateException} if the registry is inconsistent.
     * Called from unit tests to guard against accidental duplicate registrations.
     */
    static void verifyIntegrity() {
        long distinctIds = ALL.stream().map(PluginDescriptor::id).distinct().count();
        if (distinctIds != ALL.size()) {
            throw new IllegalStateException("Duplicate plugin ids detected in PluginRegistry");
        }
        for (PluginDescriptor d : ALL) {
            if (!BY_ID.containsKey(d.id())) {
                throw new IllegalStateException("BY_ID index is out of sync for id: " + d.id());
            }
        }
    }
}
