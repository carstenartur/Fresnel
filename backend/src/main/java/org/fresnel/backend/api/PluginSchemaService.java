package org.fresnel.backend.api;

import org.fresnel.optics.PluginCapability;
import org.fresnel.optics.PluginDescriptor;
import org.fresnel.optics.PluginRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads and validates the versioned parameter/UI schemas advertised by
 * {@link PluginRegistry}.
 *
 * <p>Schema resources are parsed once at application startup. The service rejects
 * missing resources, unsupported custom keywords, unknown widget/extension IDs,
 * duplicate field placement and parameter/UI version drift before an editor can
 * consume the metadata.</p>
 */
@Service
public class PluginSchemaService {

    public static final String JSON_SCHEMA_DRAFT_2020_12 =
            "https://json-schema.org/draft/2020-12/schema";
    public static final int CURRENT_UI_FORMAT_VERSION = 1;

    private static final Set<String> TRUSTED_WIDGETS = Set.of(
            "number-with-presets",
            "select",
            "radio",
            "read-only",
            "focus-point-list",
            "window-cell-layout",
            "hologram-target-image");

    private static final Set<String> TRUSTED_EXTENSIONS = Set.of(
            "production-actions",
            "validation",
            "experiment",
            "propagation",
            "preview-info",
            "reconstruction-preview");

    private static final Set<String> FRESNEL_SCHEMA_KEYWORDS = Set.of(
            "x-fresnel-unit",
            "x-fresnel-step",
            "x-fresnel-precision",
            "x-fresnel-expensive",
            "x-fresnel-widget",
            "x-fresnel-enum-labels",
            "x-fresnel-sensitive-size",
            "x-fresnel-power-of-two");

    private static final Set<String> UI_SCHEMA_FIELDS = Set.of(
            "formatVersion", "pluginId", "parameterSchemaVersion",
            "groups", "widgets", "extensions");
    private static final Set<String> UI_GROUP_FIELDS = Set.of(
            "id", "title", "fields", "collapsible", "advanced", "visibleWhen");
    private static final Set<String> UI_WIDGET_FIELDS = Set.of(
            "type", "presets", "visibleWhen", "readOnly");
    private static final Set<String> UI_CONDITION_FIELDS = Set.of(
            "path", "equals", "notEquals", "oneOf");

    private final Map<String, PluginSchemaDocument> byPluginId;
    private final List<PluginSchemaDocument> all;

    public PluginSchemaService(ObjectMapper mapper) {
        if (mapper == null) throw new IllegalArgumentException("mapper must not be null");

        LinkedHashMap<String, PluginSchemaDocument> loaded = new LinkedHashMap<>();
        Set<String> schemaIds = new HashSet<>();
        for (PluginDescriptor descriptor : PluginRegistry.ALL) {
            PluginSchemaDocument document = loadAndValidate(mapper, descriptor, schemaIds);
            if (loaded.put(descriptor.id(), document) != null) {
                throw new IllegalStateException("Duplicate plugin schema id: " + descriptor.id());
            }
        }
        this.byPluginId = Map.copyOf(loaded);
        this.all = List.copyOf(loaded.values());
    }

    /** Returns the current schema document for a stable plugin ID. */
    public Optional<PluginSchemaDocument> findByPluginId(String pluginId) {
        PluginSchemaDocument document = byPluginId.get(pluginId);
        return document == null ? Optional.empty() : Optional.of(copy(document));
    }

    /** Returns the current schema document or rejects an unknown plugin ID. */
    public PluginSchemaDocument requireByPluginId(String pluginId) {
        return findByPluginId(pluginId)
                .orElseThrow(() -> new IllegalArgumentException("unknown plugin id: " + pluginId));
    }

    /** Returns all schema documents in {@link PluginRegistry} order. */
    public List<PluginSchemaDocument> all() {
        return all.stream().map(PluginSchemaService::copy).toList();
    }

    private static PluginSchemaDocument loadAndValidate(
            ObjectMapper mapper,
            PluginDescriptor descriptor,
            Set<String> schemaIds) {
        JsonNode parameterSchema = readResource(
                mapper, descriptor.schema().parameterSchemaResource());
        JsonNode uiSchema = readResource(mapper, descriptor.schema().uiSchemaResource());

        validateParameterSchema(descriptor, parameterSchema, schemaIds);
        validateUiSchema(descriptor, parameterSchema, uiSchema);

        JsonNode defaults = parameterSchema.get("default");
        List<PluginCapability> capabilities = descriptor.capabilities().stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        return new PluginSchemaDocument(
                descriptor.id(),
                descriptor.schema().parameterSchemaVersion(),
                descriptor.schema().editorMode(),
                parameterSchema.deepCopy(),
                uiSchema.deepCopy(),
                defaults.deepCopy(),
                capabilities);
    }

    private static JsonNode readResource(ObjectMapper mapper, String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing plugin schema resource: " + resourcePath);
        }
        try (InputStream input = resource.getInputStream()) {
            JsonNode node = mapper.readTree(input);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException(
                        "Plugin schema resource must contain a JSON object: " + resourcePath);
            }
            return node;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Could not read plugin schema resource " + resourcePath + ": " + concise(e), e);
        }
    }

    private static void validateParameterSchema(
            PluginDescriptor descriptor,
            JsonNode schema,
            Set<String> schemaIds) {
        String path = descriptor.schema().parameterSchemaResource();
        requireText(schema, "$schema", path, JSON_SCHEMA_DRAFT_2020_12);
        String id = requireNonBlankText(schema, "$id", path);
        if (!schemaIds.add(id)) {
            throw new IllegalStateException("Duplicate JSON Schema $id: " + id);
        }
        requireText(schema, "type", path, "object");
        if (!schema.path("additionalProperties").isBoolean()
                || schema.path("additionalProperties").booleanValue()) {
            throw new IllegalStateException(
                    path + " must set additionalProperties to false");
        }

        JsonNode properties = requireObject(schema, "properties", path);
        if (properties.isEmpty()) {
            throw new IllegalStateException(path + " must define at least one property");
        }
        JsonNode defaults = requireObject(schema, "default", path);
        requireKnownKeys(defaults, properties, path + ".default");

        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray()) {
                throw new IllegalStateException(path + ".required must be an array");
            }
            Set<String> seen = new HashSet<>();
            for (JsonNode item : required) {
                if (!item.isTextual() || item.textValue().isBlank()) {
                    throw new IllegalStateException(path + ".required contains an invalid field");
                }
                String field = item.textValue();
                if (!properties.has(field)) {
                    throw new IllegalStateException(
                            path + ".required refers to unknown property: " + field);
                }
                if (!seen.add(field)) {
                    throw new IllegalStateException(
                            path + ".required contains duplicate property: " + field);
                }
            }
        }

        validateCustomKeywords(schema, path);
    }

    private static void validateUiSchema(
            PluginDescriptor descriptor,
            JsonNode parameterSchema,
            JsonNode uiSchema) {
        String path = descriptor.schema().uiSchemaResource();
        rejectUnknownFields(uiSchema, UI_SCHEMA_FIELDS, path);
        requireInteger(uiSchema, "formatVersion", path, CURRENT_UI_FORMAT_VERSION);
        requireText(uiSchema, "pluginId", path, descriptor.id());
        requireInteger(
                uiSchema,
                "parameterSchemaVersion",
                path,
                descriptor.schema().parameterSchemaVersion());

        Set<String> expectedFields = addressableFields(parameterSchema);
        JsonNode groups = uiSchema.get("groups");
        if (groups == null || !groups.isArray() || groups.isEmpty()) {
            throw new IllegalStateException(path + ".groups must be a non-empty array");
        }

        Set<String> groupIds = new HashSet<>();
        Set<String> groupedFields = new LinkedHashSet<>();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            JsonNode group = groups.get(groupIndex);
            String groupPath = path + ".groups[" + groupIndex + "]";
            if (!group.isObject()) {
                throw new IllegalStateException(groupPath + " must be an object");
            }
            rejectUnknownFields(group, UI_GROUP_FIELDS, groupPath);
            String groupId = requireNonBlankText(group, "id", groupPath);
            requireNonBlankText(group, "title", groupPath);
            requireOptionalBoolean(group, "collapsible", groupPath);
            requireOptionalBoolean(group, "advanced", groupPath);
            validateCondition(group.get("visibleWhen"), parameterSchema, expectedFields,
                    groupPath + ".visibleWhen");
            if (!groupIds.add(groupId)) {
                throw new IllegalStateException(path + " contains duplicate group id: " + groupId);
            }
            JsonNode fields = group.get("fields");
            if (fields == null || !fields.isArray() || fields.isEmpty()) {
                throw new IllegalStateException(groupPath + ".fields must be a non-empty array");
            }
            for (JsonNode fieldNode : fields) {
                if (!fieldNode.isTextual() || fieldNode.textValue().isBlank()) {
                    throw new IllegalStateException(groupPath + ".fields contains an invalid path");
                }
                String field = fieldNode.textValue();
                if (!expectedFields.contains(field)) {
                    throw new IllegalStateException(
                            groupPath + ".fields refers to unknown schema field: " + field);
                }
                if (!groupedFields.add(field)) {
                    throw new IllegalStateException(
                            path + " places field in more than one group: " + field);
                }
            }
        }
        if (!groupedFields.equals(expectedFields)) {
            Set<String> missing = new LinkedHashSet<>(expectedFields);
            missing.removeAll(groupedFields);
            throw new IllegalStateException(path + " does not place every parameter field; missing: " + missing);
        }

        JsonNode widgets = uiSchema.get("widgets");
        if (widgets != null) {
            if (!widgets.isObject()) {
                throw new IllegalStateException(path + ".widgets must be an object");
            }
            for (Map.Entry<String, JsonNode> entry : widgets.properties()) {
                String widgetPath = path + ".widgets." + entry.getKey();
                if (!groupedFields.contains(entry.getKey())) {
                    throw new IllegalStateException(
                            path + ".widgets refers to unknown field: " + entry.getKey());
                }
                if (!entry.getValue().isObject()) {
                    throw new IllegalStateException(widgetPath + " must be an object");
                }
                rejectUnknownFields(entry.getValue(), UI_WIDGET_FIELDS, widgetPath);
                String type = requireNonBlankText(entry.getValue(), "type", widgetPath);
                if (!TRUSTED_WIDGETS.contains(type)) {
                    throw new IllegalStateException(path + " requests untrusted widget: " + type);
                }
                requireOptionalBoolean(entry.getValue(), "readOnly", widgetPath);
                validateCondition(entry.getValue().get("visibleWhen"), parameterSchema,
                        expectedFields, widgetPath + ".visibleWhen");

                JsonNode fieldSchema = resolveFieldSchema(parameterSchema, entry.getKey());
                validateWidgetType(type, fieldSchema, widgetPath);
                JsonNode presets = entry.getValue().get("presets");
                if (presets != null) {
                    if (!"number-with-presets".equals(type)
                            || !presets.isArray() || presets.isEmpty()) {
                        throw new IllegalStateException(
                                widgetPath + ".presets is only valid as a non-empty array for number-with-presets");
                    }
                    for (JsonNode preset : presets) {
                        if (!preset.isNumber()) {
                            throw new IllegalStateException(widgetPath + ".presets must contain only numbers");
                        }
                    }
                }
            }
        }

        JsonNode extensions = uiSchema.get("extensions");
        if (extensions != null) {
            if (!extensions.isArray()) {
                throw new IllegalStateException(path + ".extensions must be an array");
            }
            Set<String> seen = new HashSet<>();
            for (JsonNode extension : extensions) {
                if (!extension.isTextual() || !TRUSTED_EXTENSIONS.contains(extension.textValue())) {
                    throw new IllegalStateException(
                            path + " requests an unknown or untrusted editor extension: " + extension);
                }
                if (!seen.add(extension.textValue())) {
                    throw new IllegalStateException(
                            path + " contains duplicate editor extension: " + extension.textValue());
                }
            }
        }
    }

    private static void validateWidgetType(String type, JsonNode fieldSchema, String path) {
        String fieldType = fieldSchema.path("type").asText();
        if ("number-with-presets".equals(type)
                && !("number".equals(fieldType) || "integer".equals(fieldType))) {
            throw new IllegalStateException(path + " requires a numeric parameter field");
        }
        if (("select".equals(type) || "radio".equals(type))
                && (!("string".equals(fieldType))
                || !fieldSchema.path("enum").isArray()
                || fieldSchema.path("enum").isEmpty())) {
            throw new IllegalStateException(path + " requires a non-empty string enum");
        }
    }

    private static void validateCondition(
            JsonNode condition,
            JsonNode parameterSchema,
            Set<String> expectedFields,
            String path) {
        if (condition == null || condition.isNull()) return;
        if (!condition.isObject()) {
            throw new IllegalStateException(path + " must be an object");
        }
        rejectUnknownFields(condition, UI_CONDITION_FIELDS, path);
        String fieldPath = requireNonBlankText(condition, "path", path);
        if (!expectedFields.contains(fieldPath)) {
            throw new IllegalStateException(path + " refers to unknown parameter field: " + fieldPath);
        }

        int operators = (condition.has("equals") ? 1 : 0)
                + (condition.has("notEquals") ? 1 : 0)
                + (condition.has("oneOf") ? 1 : 0);
        if (operators != 1) {
            throw new IllegalStateException(
                    path + " must define exactly one of equals, notEquals or oneOf");
        }

        JsonNode fieldSchema = resolveFieldSchema(parameterSchema, fieldPath);
        if (condition.has("equals")) {
            validateConditionValue(condition.get("equals"), fieldSchema, path + ".equals");
        } else if (condition.has("notEquals")) {
            validateConditionValue(condition.get("notEquals"), fieldSchema, path + ".notEquals");
        } else {
            JsonNode values = condition.get("oneOf");
            if (values == null || !values.isArray() || values.isEmpty()) {
                throw new IllegalStateException(path + ".oneOf must be a non-empty array");
            }
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < values.size(); index++) {
                JsonNode value = values.get(index);
                validateConditionValue(value, fieldSchema, path + ".oneOf[" + index + "]");
                if (!seen.add(value.toString())) {
                    throw new IllegalStateException(path + ".oneOf contains a duplicate value: " + value);
                }
            }
        }
    }

    private static void validateConditionValue(JsonNode value, JsonNode fieldSchema, String path) {
        if (value == null || value.isNull()) return;
        String type = fieldSchema.path("type").asText();
        boolean compatible = switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            default -> false;
        };
        if (!compatible) {
            throw new IllegalStateException(path + " does not match parameter type " + type);
        }
        JsonNode enumValues = fieldSchema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean found = false;
            for (JsonNode candidate : enumValues) {
                if (candidate.equals(value)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException(path + " is not a declared enum value: " + value);
            }
        }
    }

    private static Set<String> addressableFields(JsonNode parameterSchema) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectAddressableFields(parameterSchema, "", result);
        return result;
    }

    private static void collectAddressableFields(
            JsonNode schema,
            String prefix,
            Set<String> result) {
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) return;
        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode fieldSchema = entry.getValue();
            if ("object".equals(fieldSchema.path("type").asText())
                    && fieldSchema.path("properties").isObject()
                    && !fieldSchema.has("x-fresnel-widget")) {
                collectAddressableFields(fieldSchema, path, result);
            } else {
                result.add(path);
            }
        }
    }

    private static JsonNode resolveFieldSchema(JsonNode parameterSchema, String path) {
        JsonNode current = parameterSchema;
        for (String segment : path.split("\\.")) {
            JsonNode properties = current.get("properties");
            current = properties == null ? null : properties.get(segment);
            if (current == null || !current.isObject()) {
                throw new IllegalStateException("Could not resolve parameter schema field: " + path);
            }
        }
        return current;
    }

    private static void validateCustomKeywords(JsonNode node, String path) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String childPath = path + "." + entry.getKey();
                if (entry.getKey().startsWith("x-fresnel-")
                        && !FRESNEL_SCHEMA_KEYWORDS.contains(entry.getKey())) {
                    throw new IllegalStateException(
                            "Unsupported Fresnel schema keyword " + entry.getKey() + " at " + path);
                }
                validateCustomKeywords(entry.getValue(), childPath);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                validateCustomKeywords(node.get(i), path + "[" + i + "]");
            }
        }
    }

    private static void requireKnownKeys(JsonNode values, JsonNode properties, String path) {
        for (Map.Entry<String, JsonNode> entry : values.properties()) {
            if (!properties.has(entry.getKey())) {
                throw new IllegalStateException(path + " contains unknown property: " + entry.getKey());
            }
        }
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowed, String path) {
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalStateException(path + " contains unsupported field: " + entry.getKey());
            }
        }
    }

    private static void requireOptionalBoolean(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value != null && !value.isBoolean()) {
            throw new IllegalStateException(path + "." + field + " must be a boolean");
        }
    }

    private static JsonNode requireObject(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(path + "." + field + " must be an object");
        }
        return value;
    }

    private static String requireNonBlankText(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(path + "." + field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static void requireText(
            JsonNode parent,
            String field,
            String path,
            String expected) {
        String actual = requireNonBlankText(parent, field, path);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    path + "." + field + " must be " + expected + " but was " + actual);
        }
    }

    private static void requireInteger(
            JsonNode parent,
            String field,
            String path,
            int expected) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || value.intValue() != expected) {
            throw new IllegalStateException(
                    path + "." + field + " must be " + expected);
        }
    }

    private static PluginSchemaDocument copy(PluginSchemaDocument source) {
        return new PluginSchemaDocument(
                source.pluginId(),
                source.parameterSchemaVersion(),
                source.editorMode(),
                source.parameterSchema().deepCopy(),
                source.uiSchema().deepCopy(),
                source.defaults().deepCopy(),
                source.capabilities());
    }

    private static String concise(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
