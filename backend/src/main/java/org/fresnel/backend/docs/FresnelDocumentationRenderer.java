package org.fresnel.backend.docs;

import org.fresnel.backend.api.FresnelJobDocument;
import org.fresnel.backend.api.FresnelJobService;
import org.fresnel.backend.api.PluginSchemaDocument;
import org.fresnel.backend.api.PluginSchemaService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/** Generates stable documentation fragments from canonical jobs and plugin schemas. */
@Service
public final class FresnelDocumentationRenderer {

    private static final Pattern EXAMPLE_ID_SEGMENT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final FresnelJobService jobService;
    private final PluginSchemaService schemaService;

    public FresnelDocumentationRenderer(
            FresnelJobService jobService,
            PluginSchemaService schemaService) {
        this.jobService = jobService;
        this.schemaService = schemaService;
    }

    public String renderParameterTable(byte[] jobBytes) {
        return renderParameterTable(jobService.parseAndNormalize(jobBytes));
    }

    public String renderParameterTable(FresnelJobDocument job) {
        FresnelJobDocument normalized = jobService.normalize(job);
        PluginSchemaDocument schema = schemaService.requireByPluginId(
                normalized.plugin().id());

        List<Row> rows = new ArrayList<>();
        JsonNode groups = schema.uiSchema().get("groups");
        for (JsonNode group : groups) {
            JsonNode fields = group.get("fields");
            for (JsonNode field : fields) {
                String path = field.asText();
                JsonNode fieldSchema = resolveSchema(schema.parameterSchema(), path);
                JsonNode value = resolveValue(normalized.parameters(), path);
                rows.add(new Row(
                        fieldSchema.path("title").asText(path),
                        formatValue(fieldSchema, value)));
            }
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append("| Parameter | Value |\n");
        markdown.append("|---|---:|\n");
        for (Row row : rows) {
            markdown.append("| ")
                    .append(escape(row.label()))
                    .append(" | ")
                    .append(escape(row.value()))
                    .append(" |\n");
        }
        return markdown.toString();
    }

    public String renderMarkedParameterBlock(String exampleId, byte[] jobBytes) {
        String id = validateExampleId(exampleId);
        return "<!-- fresnel-example:" + id + ":start -->\n"
                + renderParameterTable(jobBytes)
                + "<!-- fresnel-example:" + id + ":end -->";
    }

    private static String validateExampleId(String exampleId) {
        if (exampleId == null || exampleId.isBlank()) {
            throw new IllegalArgumentException("documentation example id must not be empty");
        }
        String id = exampleId.trim();
        if (id.contains("--")) {
            throw new IllegalArgumentException(
                    "documentation example id must not contain an HTML comment delimiter");
        }
        for (String segment : id.split("/", -1)) {
            if (!EXAMPLE_ID_SEGMENT.matcher(segment).matches()
                    || ".".equals(segment)
                    || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "documentation example id contains an unsafe segment: " + exampleId);
            }
        }
        return id;
    }

    private static JsonNode resolveSchema(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            current = current.path("properties").get(segment);
            if (current == null || !current.isObject()) {
                throw new IllegalStateException(
                        "Could not resolve documentation schema field: " + path);
            }
        }
        return current;
    }

    private static JsonNode resolveValue(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            current = current == null ? null : current.get(segment);
            if (current == null) {
                throw new IllegalStateException(
                        "Canonical job is missing documentation field: " + path);
            }
        }
        return current;
    }

    private static String formatValue(JsonNode schema, JsonNode value) {
        if ((schema.path("x-fresnel-sensitive-size").asBoolean(false)
                || "base64".equals(schema.path("contentEncoding").asText()))
                && value.isTextual()) {
            return value.textValue().isEmpty()
                    ? "No embedded data"
                    : "Embedded data (approximately "
                            + decodedBase64Bytes(value.textValue()) + " bytes decoded)";
        }

        String rendered;
        if (value.isNumber()) {
            BigDecimal decimal = value.decimalValue();
            rendered = decimal.compareTo(BigDecimal.ZERO) == 0
                    ? "0"
                    : decimal.stripTrailingZeros().toPlainString();
        } else if (value.isTextual()) {
            rendered = formatTextValue(schema, value.textValue());
        } else if (value.isBoolean()) {
            rendered = value.booleanValue() ? "Yes" : "No";
        } else if (value.isArray()) {
            rendered = formatArray(schema.path("items"), value);
        } else if (value.isObject()) {
            rendered = formatObject(schema, value);
        } else if (value.isNull()) {
            rendered = "—";
        } else {
            rendered = value.toString();
        }

        String unit = schema.path("x-fresnel-unit").asText("");
        return unit.isBlank() ? rendered : rendered + " " + unit;
    }

    private static String formatTextValue(JsonNode schema, String value) {
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            JsonNode labels = schema.get("x-fresnel-enum-labels");
            JsonNode label = labels == null ? null : labels.get(value);
            return label != null && label.isTextual()
                    ? label.textValue()
                    : humanize(value);
        }
        return value;
    }

    private static String formatArray(JsonNode itemSchema, JsonNode values) {
        if (values.isEmpty()) return "None";
        StringJoiner joiner = new StringJoiner("; ");
        int index = 1;
        for (JsonNode value : values) {
            String item = value.isObject()
                    ? formatObject(itemSchema, value)
                    : formatValue(itemSchema, value);
            joiner.add(index++ + ". " + item);
        }
        return joiner.toString();
    }

    private static String formatObject(JsonNode schema, JsonNode value) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) return value.toString();

        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            JsonNode propertyValue = value.get(property.getKey());
            if (propertyValue == null) continue;
            String label = property.getValue().path("title").asText(property.getKey());
            joiner.add(label + " = " + formatValue(property.getValue(), propertyValue));
        }
        String rendered = joiner.toString();
        return rendered.isEmpty() ? value.toString() : rendered;
    }

    private static long decodedBase64Bytes(String value) {
        long padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        return Math.max(0, value.length() * 3L / 4L - padding);
    }

    private static String humanize(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return lower.isEmpty()
                ? lower
                : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("`", "&#96;")
                .replace("|", "\\|")
                .replace('\r', ' ')
                .replace('\n', ' ');
    }

    private record Row(String label, String value) {}
}
