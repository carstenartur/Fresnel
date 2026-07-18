package org.fresnel.backend.api;

import org.fresnel.optics.PluginDescriptor;
import org.fresnel.optics.PluginRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the security and type contract of Fresnel-specific JSON Schema
 * annotations before any schema is published to a client.
 *
 * <p>Fresnel intentionally supports an inline, data-only subset. References are
 * forbidden even though JSON Schema itself permits them: a UI consumer must never
 * turn schema data into a network fetch, dynamic module lookup or executable
 * extension point.</p>
 */
@Component
final class PluginParameterSchemaSecurityPolicy {

    private static final Set<String> TRUSTED_WIDGETS = Set.of(
            "number-with-presets",
            "select",
            "radio",
            "read-only",
            "focus-point-list",
            "window-cell-layout",
            "hologram-target-image");

    private static final Set<String> SUPPORTED_ANNOTATIONS = Set.of(
            "x-fresnel-unit",
            "x-fresnel-step",
            "x-fresnel-precision",
            "x-fresnel-expensive",
            "x-fresnel-widget",
            "x-fresnel-enum-labels",
            "x-fresnel-sensitive-size",
            "x-fresnel-power-of-two");

    private static final Set<String> FORBIDDEN_REFERENCE_KEYWORDS = Set.of(
            "$ref", "$dynamicRef", "$recursiveRef");

    PluginParameterSchemaSecurityPolicy(ObjectMapper mapper) {
        if (mapper == null) throw new IllegalArgumentException("mapper must not be null");
        for (PluginDescriptor descriptor : PluginRegistry.ALL) {
            JsonNode schema = read(mapper, descriptor.schema().parameterSchemaResource());
            validate(descriptor, schema);
        }
    }

    static void validate(PluginDescriptor descriptor, JsonNode schema) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor must not be null");
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException(
                    descriptor.schema().parameterSchemaResource() + " must contain a JSON object");
        }

        String path = descriptor.schema().parameterSchemaResource();
        requireText(schema, "$schema", path, PluginSchemaService.JSON_SCHEMA_DRAFT_2020_12);
        String expectedId = "https://carstenartur.github.io/Fresnel/schemas/plugins/"
                + descriptor.id() + "/parameters-v"
                + descriptor.schema().parameterSchemaVersion() + ".schema.json";
        requireText(schema, "$id", path, expectedId);
        validateNode(schema, path);
    }

    private static JsonNode read(ObjectMapper mapper, String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing plugin schema resource: " + resourcePath);
        }
        try (InputStream input = resource.getInputStream()) {
            return mapper.readTree(input);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "Could not read plugin schema resource " + resourcePath + ": "
                            + concise(exception), exception);
        }
    }

    private static void validateNode(JsonNode node, String path) {
        if (node.isObject()) {
            JsonNode readOnly = node.get("readOnly");
            if (readOnly != null && !readOnly.isBoolean()) {
                throw new IllegalStateException(path + ".readOnly must be a boolean");
            }

            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                String childPath = path + "." + key;

                if (FORBIDDEN_REFERENCE_KEYWORDS.contains(key)) {
                    throw new IllegalStateException(
                            "Schema references are not supported by Fresnel: " + childPath);
                }
                if (key.startsWith("x-fresnel-")) {
                    if (!SUPPORTED_ANNOTATIONS.contains(key)) {
                        throw new IllegalStateException(
                                "Unsupported Fresnel schema keyword " + key + " at " + path);
                    }
                    validateAnnotation(node, key, value, childPath);
                }
                validateNode(value, childPath);
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateNode(node.get(index), path + "[" + index + "]");
            }
        }
    }

    private static void validateAnnotation(
            JsonNode fieldSchema,
            String key,
            JsonNode value,
            String path) {
        switch (key) {
            case "x-fresnel-unit" -> requireNonBlankString(value, path);
            case "x-fresnel-step" -> {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())
                        || value.doubleValue() <= 0.0) {
                    throw new IllegalStateException(path + " must be a finite positive number");
                }
                String type = fieldSchema.path("type").asText();
                if (!("number".equals(type) || "integer".equals(type))) {
                    throw new IllegalStateException(path + " is only valid on numeric fields");
                }
            }
            case "x-fresnel-precision" -> {
                if (!value.isIntegralNumber() || value.intValue() < 0 || value.intValue() > 15) {
                    throw new IllegalStateException(path + " must be an integer from 0 through 15");
                }
            }
            case "x-fresnel-expensive", "x-fresnel-sensitive-size" ->
                    requireBoolean(value, path);
            case "x-fresnel-power-of-two" -> {
                requireBoolean(value, path);
                if (!"integer".equals(fieldSchema.path("type").asText())) {
                    throw new IllegalStateException(path + " is only valid on integer fields");
                }
            }
            case "x-fresnel-widget" -> {
                String widget = requireNonBlankString(value, path);
                if (!TRUSTED_WIDGETS.contains(widget)) {
                    throw new IllegalStateException(path + " requests untrusted widget: " + widget);
                }
            }
            case "x-fresnel-enum-labels" -> validateEnumLabels(fieldSchema, value, path);
            default -> throw new IllegalStateException("Unsupported Fresnel annotation: " + key);
        }
    }

    private static void validateEnumLabels(
            JsonNode fieldSchema,
            JsonNode labels,
            String path) {
        if (!labels.isObject()) {
            throw new IllegalStateException(path + " must be an object");
        }
        JsonNode enumValues = fieldSchema.get("enum");
        if (!"string".equals(fieldSchema.path("type").asText())
                || enumValues == null || !enumValues.isArray() || enumValues.isEmpty()) {
            throw new IllegalStateException(path + " requires a non-empty string enum");
        }
        Set<String> allowed = new java.util.HashSet<>();
        for (JsonNode enumValue : enumValues) {
            if (!enumValue.isTextual()) {
                throw new IllegalStateException(path + " requires textual enum values");
            }
            allowed.add(enumValue.textValue());
        }
        for (Map.Entry<String, JsonNode> label : labels.properties()) {
            if (!allowed.contains(label.getKey())) {
                throw new IllegalStateException(
                        path + " contains a label for unknown enum value: " + label.getKey());
            }
            requireNonBlankString(label.getValue(), path + "." + label.getKey());
        }
    }

    private static String requireNonBlankString(JsonNode value, String path) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(path + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static void requireBoolean(JsonNode value, String path) {
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException(path + " must be a boolean");
        }
    }

    private static void requireText(
            JsonNode parent,
            String field,
            String path,
            String expected) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
            throw new IllegalStateException(
                    path + "." + field + " must be " + expected);
        }
    }

    private static String concise(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
