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

/** Generates stable documentation fragments from canonical jobs and plugin schemas. */
@Service
public final class FresnelDocumentationRenderer {

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
        if (exampleId == null || exampleId.isBlank()) {
            throw new IllegalArgumentException("documentation example id must not be empty");
        }
        String id = exampleId.trim();
        return "<!-- fresnel-example:" + id + ":start -->\n"
                + renderParameterTable(jobBytes)
                + "<!-- fresnel-example:" + id + ":end -->";
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
        String rendered;
        if (value.isNumber()) {
            BigDecimal decimal = value.decimalValue();
            rendered = decimal.compareTo(BigDecimal.ZERO) == 0
                    ? "0"
                    : decimal.stripTrailingZeros().toPlainString();
        } else if (value.isTextual()) {
            JsonNode labels = schema.get("x-fresnel-enum-labels");
            JsonNode label = labels == null ? null : labels.get(value.textValue());
            rendered = label != null && label.isTextual()
                    ? label.textValue()
                    : humanize(value.textValue());
        } else if (value.isBoolean()) {
            rendered = value.booleanValue() ? "Yes" : "No";
        } else if (value.isNull()) {
            rendered = "—";
        } else {
            rendered = value.toString();
        }

        String unit = schema.path("x-fresnel-unit").asText("");
        return unit.isBlank() ? rendered : rendered + " " + unit;
    }

    private static String humanize(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return lower.isEmpty()
                ? lower
                : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private record Row(String label, String value) {}
}
