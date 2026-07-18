package org.fresnel.optics;

/**
 * Versioned classpath resources that define one plugin's public parameter and UI
 * contract.
 *
 * @param parameterSchemaVersion public compatibility version used by `.fresnel`
 *                               job documents
 * @param parameterSchemaResource classpath path of the Draft 2020-12 parameter
 *                                schema
 * @param uiSchemaResource classpath path of the separate presentation schema
 * @param editorMode whether the common schema editor is sufficient or extended
 */
public record PluginSchemaDescriptor(
        int parameterSchemaVersion,
        String parameterSchemaResource,
        String uiSchemaResource,
        PluginEditorMode editorMode
) {

    public PluginSchemaDescriptor {
        if (parameterSchemaVersion < 1) {
            throw new IllegalArgumentException("parameterSchemaVersion must be at least 1");
        }
        parameterSchemaResource = requireSafeResource(
                parameterSchemaResource, "parameterSchemaResource");
        uiSchemaResource = requireSafeResource(uiSchemaResource, "uiSchemaResource");
        if (editorMode == null) {
            throw new IllegalArgumentException("editorMode must not be null");
        }
    }

    private static String requireSafeResource(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains("\\")) {
            throw new IllegalArgumentException(name + " must be a relative classpath resource");
        }
        return normalized;
    }
}
