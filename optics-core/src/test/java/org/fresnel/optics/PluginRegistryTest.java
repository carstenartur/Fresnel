package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRegistryTest {

    @Test
    void registryContainsAllExpectedPlugins() {
        Set<String> ids = new HashSet<>();
        for (PluginDescriptor descriptor : PluginRegistry.ALL) ids.add(descriptor.id());
        assertTrue(ids.contains("zone-plate"), "zone-plate missing");
        assertTrue(ids.contains("rgb-zone-plate"), "rgb-zone-plate missing");
        assertTrue(ids.contains("multi-focus"), "multi-focus missing");
        assertTrue(ids.contains("hex-macro-cell"), "hex-macro-cell missing");
        assertTrue(ids.contains("window-foil"), "window-foil missing");
        assertTrue(ids.contains("hologram"), "hologram missing");
        assertEquals(6, PluginRegistry.ALL.size(), "unexpected plugin count");
    }

    @Test
    void registryOrderIsThePublicNavigationOrder() {
        assertEquals(List.of(
                        "zone-plate",
                        "hex-macro-cell",
                        "window-foil",
                        "multi-focus",
                        "rgb-zone-plate",
                        "hologram"),
                PluginRegistry.ALL.stream().map(PluginDescriptor::id).toList());
    }

    @Test
    void allPluginsHaveNonBlankRequiredFields() {
        for (PluginDescriptor descriptor : PluginRegistry.ALL) {
            assertNotNull(descriptor.id(), descriptor.id() + ": id is null");
            assertFalse(descriptor.id().isBlank(), descriptor.id() + ": id is blank");
            assertNotNull(descriptor.displayName(), descriptor.id() + ": displayName is null");
            assertFalse(descriptor.displayName().isBlank(), descriptor.id() + ": displayName is blank");
            assertNotNull(descriptor.description(), descriptor.id() + ": description is null");
            assertNotNull(descriptor.rendererClass(), descriptor.id() + ": rendererClass is null");
            assertNotNull(descriptor.parameterType(), descriptor.id() + ": parameterType is null");
            assertNotNull(descriptor.documentationUrl(), descriptor.id() + ": documentationUrl is null");
            assertNotNull(descriptor.stability(), descriptor.id() + ": stability is null");
            assertNotNull(descriptor.capabilities(), descriptor.id() + ": capabilities is null");
            assertNotNull(descriptor.propagationModes(), descriptor.id() + ": propagationModes is null");
            assertNotNull(descriptor.schema(), descriptor.id() + ": schema is null");
        }
    }

    @Test
    void pluginIdsAreUnique() {
        long distinct = PluginRegistry.ALL.stream()
                .map(PluginDescriptor::id)
                .distinct()
                .count();
        assertEquals(PluginRegistry.ALL.size(), distinct, "duplicate plugin ids detected");
    }

    @Test
    void pluginIdsAreLowercaseAndHyphenated() {
        for (PluginDescriptor descriptor : PluginRegistry.ALL) {
            assertEquals(descriptor.id().toLowerCase(java.util.Locale.ROOT), descriptor.id(),
                    descriptor.id() + ": id must be lowercase");
            assertTrue(descriptor.id().matches("[a-z][a-z0-9-]*"),
                    descriptor.id() + ": id must match [a-z][a-z0-9-]*");
        }
    }

    @Test
    void integrityCheckPasses() {
        assertDoesNotThrow(PluginRegistry::verifyIntegrity);
    }

    @Test
    void findByIdReturnsCorrectDescriptor() {
        PluginDescriptor zonePlate = PluginRegistry.findById("zone-plate").orElseThrow();
        assertEquals("zone-plate", zonePlate.id());
        assertEquals("ZonePlateRenderer", zonePlate.rendererClass());
        assertEquals("SingleZonePlateParameters", zonePlate.parameterType());
        assertEquals(PluginEditorMode.SCHEMA_WITH_EXTENSIONS, zonePlate.schema().editorMode());
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertTrue(PluginRegistry.findById("does-not-exist").isEmpty());
    }

    @Test
    void requireByIdThrowsForUnknownId() {
        assertThrows(IllegalArgumentException.class,
                () -> PluginRegistry.requireById("no-such-plugin"));
    }

    @Test
    void hasPluginReturnsTrueForRegisteredId() {
        assertTrue(PluginRegistry.hasPlugin("hologram"));
    }

    @Test
    void hasPluginReturnsFalseForUnknownId() {
        assertFalse(PluginRegistry.hasPlugin("unknown"));
    }

    @Test
    void withCapabilityReturnsPdfPlugins() {
        List<PluginDescriptor> pdf = PluginRegistry.withCapability(PluginCapability.EXPORT_PDF);
        assertTrue(pdf.size() >= 2, "expected at least zone-plate and hex-macro-cell");
        assertTrue(pdf.stream().anyMatch(descriptor -> descriptor.id().equals("zone-plate")));
        assertTrue(pdf.stream().anyMatch(descriptor -> descriptor.id().equals("hex-macro-cell")));
        assertTrue(pdf.stream().anyMatch(descriptor -> descriptor.id().equals("window-foil")));
    }

    @Test
    void withCapabilityExportStlReturnsOnlyHologram() {
        List<PluginDescriptor> stl = PluginRegistry.withCapability(PluginCapability.EXPORT_STL);
        assertEquals(1, stl.size());
        assertEquals("hologram", stl.getFirst().id());
    }

    @Test
    void withCapabilityPropagationPreviewReturnsOnlyZonePlate() {
        List<PluginDescriptor> propagation =
                PluginRegistry.withCapability(PluginCapability.PROPAGATION_PREVIEW);
        assertEquals(1, propagation.size());
        assertEquals("zone-plate", propagation.getFirst().id());
    }

    @Test
    void withCapabilityOpticalQualityReportReturnsOnlyZonePlate() {
        List<PluginDescriptor> reports =
                PluginRegistry.withCapability(PluginCapability.OPTICAL_QUALITY_REPORT);
        assertEquals(1, reports.size());
        assertEquals("zone-plate", reports.getFirst().id());
    }

    @Test
    void zonePlateDescriptorHasExpectedCapabilities() {
        PluginDescriptor zonePlate = PluginRegistry.ZONE_PLATE;
        assertTrue(zonePlate.supports(PluginCapability.EXPORT_PNG));
        assertTrue(zonePlate.supports(PluginCapability.EXPORT_SVG));
        assertTrue(zonePlate.supports(PluginCapability.EXPORT_PDF));
        assertTrue(zonePlate.supports(PluginCapability.EXPORT_DXF));
        assertTrue(zonePlate.supports(PluginCapability.EXPORT_GERBER));
        assertTrue(zonePlate.supports(PluginCapability.PREVIEW_PNG));
        assertTrue(zonePlate.supports(PluginCapability.PROPAGATION_PREVIEW));
        assertTrue(zonePlate.supportsPrintabilityAnalysis());
        assertTrue(zonePlate.supportsOpticalQualityReport());
        assertFalse(zonePlate.supportsExperimentalValidation());
    }

    @Test
    void zonePlateDescriptorHasBothPropagationModes() {
        PluginDescriptor zonePlate = PluginRegistry.ZONE_PLATE;
        assertTrue(zonePlate.supports(PropagationMode.FRESNEL_TF));
        assertTrue(zonePlate.supports(PropagationMode.FRAUNHOFER));
    }

    @Test
    void hologramDescriptorHasStlAndPng() {
        PluginDescriptor hologram = PluginRegistry.HOLOGRAM;
        assertTrue(hologram.supports(PluginCapability.EXPORT_STL));
        assertTrue(hologram.supports(PluginCapability.EXPORT_PNG));
        assertFalse(hologram.supports(PluginCapability.EXPORT_PDF));
        assertTrue(hologram.propagationModes().isEmpty());
    }

    @Test
    void allPluginsHaveAtLeastOnePngCapability() {
        for (PluginDescriptor descriptor : PluginRegistry.ALL) {
            assertTrue(
                    descriptor.supports(PluginCapability.EXPORT_PNG)
                            || descriptor.supports(PluginCapability.PREVIEW_PNG),
                    descriptor.id() + " must support at least one PNG capability");
        }
    }
}
