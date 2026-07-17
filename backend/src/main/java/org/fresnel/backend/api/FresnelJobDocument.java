package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Versioned, JSON-based exchange document for a Fresnel design or production job.
 *
 * <p>The public contract deliberately contains stable plugin ids and plain data only.
 * Renderer class names, Java code and other executable implementation references are
 * not part of the format.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FresnelJobDocument(
        @JsonProperty("$schema") String schema,
        String format,
        int formatVersion,
        PluginRef plugin,
        JsonNode parameters,
        ProductionPlan production,
        Provenance provenance
) {

    public static final String FORMAT_IDENTIFIER = "io.github.carstenartur.fresnel.job";
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int CURRENT_PARAMETER_SCHEMA_VERSION = 1;
    public static final String MEDIA_TYPE = "application/vnd.carstenartur.fresnel.job+json";
    public static final String FILE_EXTENSION = ".fresnel";
    public static final String SCHEMA_URL =
            "https://carstenartur.github.io/Fresnel/schemas/fresnel-job-v1.schema.json";
    public static final int MAX_FILE_BYTES = 1024 * 1024;

    /** Stable reference to the design plugin and its public compatibility versions. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PluginRef(
            String id,
            Integer parameterSchemaVersion,
            String algorithmVersion
    ) {
        public PluginRef {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Fresnel job plugin.id must not be empty");
            }
            if (parameterSchemaVersion == null || parameterSchemaVersion < 1) {
                throw new IllegalArgumentException(
                        "Fresnel job plugin.parameterSchemaVersion must be at least 1");
            }
            if (algorithmVersion == null || algorithmVersion.isBlank()) {
                throw new IllegalArgumentException(
                        "Fresnel job plugin.algorithmVersion must not be empty");
            }
            id = id.trim();
            algorithmVersion = algorithmVersion.trim();
        }
    }

    /** Optional non-empty list of outputs requested from the design. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductionPlan(List<ProductionOutput> outputs) {
        public ProductionPlan {
            // Keep null entries until the canonical service can report their exact
            // array index instead of failing inside List.copyOf during JSON binding.
            outputs = outputs == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(outputs));
        }
    }

    /**
     * One requested output. Format-specific options may use the common PDF fields or
     * the optional data-only {@code options} object.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductionOutput(
            String id,
            String format,
            String filename,
            String sheet,
            Double printScale,
            JsonNode options
    ) {}

    /** Non-sensitive metadata needed to diagnose and reproduce a design. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Provenance(
            String createdWith,
            String applicationVersion,
            String parameterSha256
    ) {}
}
