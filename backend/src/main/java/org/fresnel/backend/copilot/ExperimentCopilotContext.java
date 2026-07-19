package org.fresnel.backend.copilot;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Only the bounded schema context exposed to an experiment-copilot provider. */
public record ExperimentCopilotContext(
        String request,
        JsonNode parameterSchema,
        JsonNode defaults,
        JsonNode currentParameters
) {
    static final int MAX_REQUEST_CHARS = 8_000;
    static final int MAX_CURRENT_PARAMETERS_BYTES = 64 * 1024;

    public ExperimentCopilotContext {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("experiment request must not be blank");
        }
        request = request.trim();
        if (request.length() > MAX_REQUEST_CHARS) {
            throw new IllegalArgumentException(
                    "experiment request must not exceed " + MAX_REQUEST_CHARS + " characters");
        }

        if (parameterSchema == null || !parameterSchema.isObject()) {
            throw new IllegalArgumentException(
                    "experiment provider context requires a parameter schema object");
        }
        parameterSchema = parameterSchema.deepCopy();
        defaults = defaults == null ? null : defaults.deepCopy();
        currentParameters = validateCurrentParameters(parameterSchema, currentParameters);
    }

    private static JsonNode validateCurrentParameters(
            JsonNode parameterSchema,
            JsonNode supplied) {
        if (supplied == null || supplied.isNull()) return null;
        if (!supplied.isObject()) {
            throw new IllegalArgumentException("currentParameters must be a JSON object");
        }
        if (supplied.toString().getBytes(StandardCharsets.UTF_8).length
                > MAX_CURRENT_PARAMETERS_BYTES) {
            throw new IllegalArgumentException(
                    "currentParameters exceeds the provider-context size limit");
        }

        JsonNode properties = parameterSchema.path("properties");
        if (!properties.isObject()) {
            throw new IllegalArgumentException(
                    "currentParameters require a bounded schema properties object");
        }
        for (Map.Entry<String, JsonNode> entry : supplied.properties()) {
            JsonNode fieldSchema = properties.get(entry.getKey());
            if (fieldSchema == null) {
                throw new IllegalArgumentException(
                        "currentParameters contains unknown field: " + entry.getKey());
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                throw new IllegalArgumentException(
                        "currentParameters." + entry.getKey() + " must not be null");
            }
            String type = fieldSchema.path("type").asText();
            if (!matchesType(value, type)) {
                throw new IllegalArgumentException(
                        "currentParameters." + entry.getKey()
                                + " does not match schema type " + type);
            }
            JsonNode allowed = fieldSchema.path("enum");
            if (allowed.isArray() && !contains(allowed, value)) {
                throw new IllegalArgumentException(
                        "currentParameters." + entry.getKey()
                                + " is not an allowed enum value");
            }
        }
        return supplied.deepCopy();
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> false;
        };
    }

    private static boolean contains(JsonNode array, JsonNode candidate) {
        for (JsonNode value : array) {
            if (value.equals(candidate)) return true;
        }
        return false;
    }
}
