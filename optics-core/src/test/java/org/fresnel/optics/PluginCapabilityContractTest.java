package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards capability-driven UIs against advertising actions without an implementation. */
class PluginCapabilityContractTest {

    @Test
    void currentPluginsAdvertiseOnlyImplementedProductionAndPreviewActions() {
        assertEquals(Set.of(
                        PluginCapability.EXPORT_PNG,
                        PluginCapability.EXPORT_SVG,
                        PluginCapability.EXPORT_PDF,
                        PluginCapability.EXPORT_DXF,
                        PluginCapability.EXPORT_GERBER,
                        PluginCapability.PREVIEW_PNG,
                        PluginCapability.PROPAGATION_PREVIEW,
                        PluginCapability.PRINTABILITY_ANALYSIS,
                        PluginCapability.OPTICAL_QUALITY_REPORT),
                PluginRegistry.ZONE_PLATE.capabilities());

        assertEquals(Set.of(
                        PluginCapability.EXPORT_PNG,
                        PluginCapability.EXPORT_PDF,
                        PluginCapability.PREVIEW_PNG),
                PluginRegistry.HEX_MACRO_CELL.capabilities());

        assertEquals(Set.of(
                        PluginCapability.EXPORT_PNG,
                        PluginCapability.EXPORT_PDF,
                        PluginCapability.PREVIEW_PNG),
                PluginRegistry.WINDOW_FOIL.capabilities());

        assertEquals(Set.of(
                        PluginCapability.EXPORT_PNG,
                        PluginCapability.PREVIEW_PNG),
                PluginRegistry.MULTI_FOCUS.capabilities());

        assertEquals(Set.of(
                        PluginCapability.EXPORT_PNG,
                        PluginCapability.PREVIEW_PNG),
                PluginRegistry.RGB_ZONE_PLATE.capabilities());

        assertEquals(Set.of(
                        PluginCapability.EXPORT_PNG,
                        PluginCapability.EXPORT_STL,
                        PluginCapability.PREVIEW_PNG),
                PluginRegistry.HOLOGRAM.capabilities());
    }
}
