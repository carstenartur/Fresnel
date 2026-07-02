package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {

    // ---- Completeness ----

    @Test
    void registryContainsAllExpectedPlugins() {
        Set<String> ids = new HashSet<>();
        for (PluginDescriptor d : PluginRegistry.ALL) {
            ids.add(d.id());
        }
        assertTrue(ids.contains("zone-plate"),     "zone-plate missing");
        assertTrue(ids.contains("rgb-zone-plate"),  "rgb-zone-plate missing");
        assertTrue(ids.contains("multi-focus"),     "multi-focus missing");
        assertTrue(ids.contains("hex-macro-cell"),  "hex-macro-cell missing");
        assertTrue(ids.contains("window-foil"),     "window-foil missing");
        assertTrue(ids.contains("hologram"),        "hologram missing");
        assertEquals(6, PluginRegistry.ALL.size(), "unexpected plugin count");
    }

    @Test
    void allPluginsHaveNonBlankRequiredFields() {
        for (PluginDescriptor d : PluginRegistry.ALL) {
            assertNotNull(d.id(),            d.id() + ": id is null");
            assertFalse(d.id().isBlank(),    d.id() + ": id is blank");
            assertNotNull(d.displayName(),   d.id() + ": displayName is null");
            assertFalse(d.displayName().isBlank(), d.id() + ": displayName is blank");
            assertNotNull(d.description(),   d.id() + ": description is null");
            assertNotNull(d.rendererClass(), d.id() + ": rendererClass is null");
            assertNotNull(d.parameterType(), d.id() + ": parameterType is null");
            assertNotNull(d.frontendModeId(), d.id() + ": frontendModeId is null");
            assertNotNull(d.stability(),     d.id() + ": stability is null");
            assertNotNull(d.capabilities(),  d.id() + ": capabilities is null");
            assertNotNull(d.propagationModes(), d.id() + ": propagationModes is null");
        }
    }

    // ---- Uniqueness ----

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
        for (PluginDescriptor d : PluginRegistry.ALL) {
            assertEquals(d.id().toLowerCase(java.util.Locale.ROOT), d.id(),
                    d.id() + ": id must be lowercase");
            assertTrue(d.id().matches("[a-z][a-z0-9-]*"),
                    d.id() + ": id must match [a-z][a-z0-9-]*");
        }
    }

    @Test
    void frontendModeIdsAreUnique() {
        long distinct = PluginRegistry.ALL.stream()
                .map(PluginDescriptor::frontendModeId)
                .distinct()
                .count();
        assertEquals(PluginRegistry.ALL.size(), distinct, "duplicate frontendModeId values detected");
    }

    @Test
    void integrityCheckPasses() {
        assertDoesNotThrow(PluginRegistry::verifyIntegrity);
    }

    // ---- Lookup ----

    @Test
    void findByIdReturnsCorrectDescriptor() {
        PluginDescriptor zp = PluginRegistry.findById("zone-plate").orElseThrow();
        assertEquals("zone-plate", zp.id());
        assertEquals("ZonePlateRenderer", zp.rendererClass());
        assertEquals("SingleZonePlateParameters", zp.parameterType());
        assertEquals("single", zp.frontendModeId());
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

    // ---- Capability queries ----

    @Test
    void withCapabilityReturnsPdfPlugins() {
        List<PluginDescriptor> pdf = PluginRegistry.withCapability(PluginCapability.EXPORT_PDF);
        assertTrue(pdf.size() >= 2, "expected at least zone-plate and hex-macro-cell");
        assertTrue(pdf.stream().anyMatch(d -> d.id().equals("zone-plate")));
        assertTrue(pdf.stream().anyMatch(d -> d.id().equals("hex-macro-cell")));
        assertTrue(pdf.stream().anyMatch(d -> d.id().equals("window-foil")));
    }

    @Test
    void withCapabilityExportStlReturnsOnlyHologram() {
        List<PluginDescriptor> stl = PluginRegistry.withCapability(PluginCapability.EXPORT_STL);
        assertEquals(1, stl.size());
        assertEquals("hologram", stl.get(0).id());
    }

    @Test
    void withCapabilityPropagationPreviewReturnsOnlyZonePlate() {
        List<PluginDescriptor> prop = PluginRegistry.withCapability(PluginCapability.PROPAGATION_PREVIEW);
        assertEquals(1, prop.size());
        assertEquals("zone-plate", prop.get(0).id());
    }

    @Test
    void withCapabilityOpticalQualityReportReturnsOnlyZonePlate() {
        List<PluginDescriptor> qr = PluginRegistry.withCapability(PluginCapability.OPTICAL_QUALITY_REPORT);
        assertEquals(1, qr.size());
        assertEquals("zone-plate", qr.get(0).id());
    }

    // ---- Zone plate descriptor detail ----

    @Test
    void zonePlateDescriptorHasExpectedCapabilities() {
        PluginDescriptor zp = PluginRegistry.ZONE_PLATE;
        assertTrue(zp.supports(PluginCapability.EXPORT_PNG));
        assertTrue(zp.supports(PluginCapability.EXPORT_SVG));
        assertTrue(zp.supports(PluginCapability.EXPORT_PDF));
        assertTrue(zp.supports(PluginCapability.EXPORT_DXF));
        assertTrue(zp.supports(PluginCapability.EXPORT_GERBER));
        assertTrue(zp.supports(PluginCapability.PREVIEW_PNG));
        assertTrue(zp.supports(PluginCapability.PROPAGATION_PREVIEW));
        assertTrue(zp.supportsPrintabilityAnalysis());
        assertTrue(zp.supportsOpticalQualityReport());
        assertFalse(zp.supportsExperimentalValidation());
    }

    @Test
    void zonePlateDescriptorHasBothPropagationModes() {
        PluginDescriptor zp = PluginRegistry.ZONE_PLATE;
        assertTrue(zp.supports(PropagationMode.FRESNEL_TF));
        assertTrue(zp.supports(PropagationMode.FRAUNHOFER));
    }

    @Test
    void hologramDescriptorHasStlAndPng() {
        PluginDescriptor h = PluginRegistry.HOLOGRAM;
        assertTrue(h.supports(PluginCapability.EXPORT_STL));
        assertTrue(h.supports(PluginCapability.EXPORT_PNG));
        assertFalse(h.supports(PluginCapability.EXPORT_PDF));
        assertTrue(h.propagationModes().isEmpty());
    }

    @Test
    void allPluginsHaveAtLeastOnePngCapability() {
        for (PluginDescriptor d : PluginRegistry.ALL) {
            assertTrue(
                    d.supports(PluginCapability.EXPORT_PNG) || d.supports(PluginCapability.PREVIEW_PNG),
                    d.id() + " must support at least one PNG capability");
        }
    }
}
