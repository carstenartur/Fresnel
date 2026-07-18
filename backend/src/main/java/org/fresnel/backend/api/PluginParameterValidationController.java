package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.fresnel.optics.PluginRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Structural parameter validation for schema-driven plugin editors.
 *
 * <p>The endpoint deliberately delegates normalization to {@link FresnelJobService}
 * by wrapping the submitted object in an in-memory canonical job. GUI validation,
 * file import and later CLI execution therefore share the same accepted DTOs,
 * defaults and unknown-field checks.</p>
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginParameterValidationController {

    private final FresnelJobService jobService;
    private final PluginSchemaService schemaService;

    public PluginParameterValidationController(
            FresnelJobService jobService,
            PluginSchemaService schemaService) {
        this.jobService = jobService;
        this.schemaService = schemaService;
    }

    @PostMapping(
            value = "/{id}/parameters/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ParameterValidationResponse> validate(
            @PathVariable("id") String pluginId,
            @RequestBody JsonNode parameters) {
        if (!PluginRegistry.hasPlugin(pluginId)) {
            return ResponseEntity.notFound().build();
        }

        int schemaVersion = schemaService.requireByPluginId(pluginId).parameterSchemaVersion();
        FresnelJobDocument candidate = new FresnelJobDocument(
                FresnelJobDocument.SCHEMA_URL,
                FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION,
                new FresnelJobDocument.PluginRef(pluginId, schemaVersion, pluginId + "/1"),
                parameters,
                null,
                null);

        try {
            JsonNode normalized = jobService.normalize(candidate).parameters();
            return ResponseEntity.ok(new ParameterValidationResponse(
                    pluginId,
                    schemaVersion,
                    true,
                    normalized,
                    List.of()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.ok(new ParameterValidationResponse(
                    pluginId,
                    schemaVersion,
                    false,
                    null,
                    extractErrors(pluginId, exception)));
        }
    }

    private static List<ParameterFieldError> extractErrors(
            String pluginId,
            IllegalArgumentException exception) {
        String message = safeMessage(exception);
        String validationPrefix = "Invalid parameters for plugin ";
        if (message.startsWith(validationPrefix)) {
            int detailStart = message.indexOf(": ", validationPrefix.length());
            if (detailStart > 0) {
                String validationTarget = message.substring(
                        validationPrefix.length(), detailStart);
                String nestedPrefix = validationTarget.equals(pluginId)
                        ? ""
                        : validationTarget.startsWith(pluginId + ".")
                        ? validationTarget.substring(pluginId.length() + 1) + "."
                        : "";
                String details = message.substring(detailStart + 2);
                List<ParameterFieldError> errors = new ArrayList<>();
                for (String detail : details.split(";\\s*")) {
                    int separator = detail.indexOf(": ");
                    if (separator > 0) {
                        String path = nestedPrefix + detail.substring(0, separator).trim();
                        String fieldMessage = detail.substring(separator + 2).trim();
                        errors.add(new ParameterFieldError(
                                normalizePath(path),
                                "CONSTRAINT_VIOLATION",
                                fieldMessage));
                    }
                }
                if (!errors.isEmpty()) return List.copyOf(errors);
            }
        }

        String unknownPrefix = "Unknown field parameters.";
        if (message.startsWith(unknownPrefix)) {
            String remainder = message.substring(unknownPrefix.length());
            int end = remainder.indexOf(' ');
            String path = end < 0 ? remainder : remainder.substring(0, end);
            return List.of(new ParameterFieldError(
                    normalizePath(path),
                    "UNKNOWN_FIELD",
                    message));
        }

        if (message.startsWith("parameters.")) {
            int end = message.indexOf(' ');
            String path = end < 0 ? message : message.substring(0, end);
            return List.of(new ParameterFieldError(
                    normalizePath(path.substring("parameters.".length())),
                    classify(message),
                    message));
        }

        return List.of(new ParameterFieldError("$", classify(message), message));
    }

    private static String classify(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown field")) return "UNKNOWN_FIELD";
        if (lower.contains("deserialize") || lower.contains("must be a json")) return "INVALID_TYPE";
        if (lower.contains("must not be") || lower.contains("must be greater")
                || lower.contains("must be less") || lower.contains("must be positive")) {
            return "CONSTRAINT_VIOLATION";
        }
        return "INVALID_PARAMETERS";
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "$";
        String normalized = path.trim();
        return normalized.startsWith("parameters.")
                ? normalized.substring("parameters.".length())
                : normalized;
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "The plugin parameters are invalid.";
        String oneLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 1000 ? oneLine : oneLine.substring(0, 1000);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParameterValidationResponse(
            String pluginId,
            int parameterSchemaVersion,
            boolean valid,
            JsonNode normalizedParameters,
            List<ParameterFieldError> errors
    ) {
        public ParameterValidationResponse {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    public record ParameterFieldError(String path, String code, String message) {}
}
