package org.fresnel.backend.docs;

import org.fresnel.optics.PluginCapability;
import org.fresnel.optics.PluginKind;
import org.fresnel.optics.PluginRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundOrientedSchlierenDocumentationTest {

    @Test
    void registryDocumentationAndExampleDescribeTheImplementedSlice() throws Exception {
        var descriptor = PluginRegistry.BACKGROUND_ORIENTED_SCHLIEREN;
        assertEquals(PluginKind.MEASUREMENT, descriptor.kind());
        assertEquals(Set.of(
                        PluginCapability.GENERATE_CAPTURE_TARGET,
                        PluginCapability.PREVIEW_PNG,
                        PluginCapability.EXPORT_PNG),
                descriptor.capabilities());

        String documentation = Files.readString(Path.of(descriptor.documentationUrl()));
        assertTrue(documentation.contains("patternSeed"));
        assertTrue(documentation.contains("does not advertise")
                || documentation.contains("not advertised"));

        String example = Files.readString(
                Path.of("docs/examples/background-oriented-schlieren-target.fresnel"));
        assertTrue(example.contains("\"background-oriented-schlieren\""));
        assertTrue(example.contains("\"patternSeed\": 20260824"));
        assertTrue(!example.toLowerCase(java.util.Locale.ROOT).contains("password"));
    }
}
