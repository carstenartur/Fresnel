package org.fresnel.backend.api;

import org.fresnel.measurement.BosTargetParameters;
import org.fresnel.optics.PluginDescriptor;
import org.fresnel.optics.PluginRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginAlgorithmVersionContractTest {

    @Test
    void everyRegisteredAlgorithmVersionIsNonBlankAndPluginNamespaced() {
        for (PluginDescriptor descriptor : PluginRegistry.ALL) {
            assertFalse(descriptor.algorithmVersion().isBlank(), descriptor.id());
            assertTrue(descriptor.algorithmVersion().startsWith(descriptor.id()),
                    descriptor.id() + " algorithm version must be namespaced by the plugin id");
        }
    }

    @Test
    void bosPluginAndTargetGeneratorVersionsRemainExplicitlySeparated() {
        PluginDescriptor bos = PluginRegistry.BACKGROUND_ORIENTED_SCHLIEREN;
        assertEquals("background-oriented-schlieren/1", bos.algorithmVersion());
        assertEquals("background-oriented-schlieren-target/1",
                BosTargetParameters.ALGORITHM_VERSION);
        assertNotEquals(BosTargetParameters.ALGORITHM_VERSION, bos.algorithmVersion());
        assertEquals(
                bos.algorithmVersion(),
                PluginController.PluginMetadata.from(bos).algorithmVersion());
    }
}
