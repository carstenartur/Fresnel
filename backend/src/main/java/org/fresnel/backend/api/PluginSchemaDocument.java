package org.fresnel.backend.api;

import org.fresnel.optics.PluginCapability;
import org.fresnel.optics.PluginEditorMode;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** Deterministic public response for one plugin's parameter and UI schemas. */
public record PluginSchemaDocument(
        String pluginId,
        int parameterSchemaVersion,
        PluginEditorMode editorMode,
        JsonNode parameterSchema,
        JsonNode uiSchema,
        JsonNode defaults,
        List<PluginCapability> capabilities
) {

    public PluginSchemaDocument {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
