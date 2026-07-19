package org.fresnel.backend.copilot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI Responses API implementation using strict structured output. */
@Component
public final class OpenAiExperimentCopilotProvider implements ExperimentCopilotProvider {

    private static final List<String> PARAMETER_PATHS = List.of(
            "apertureDiameterMm", "focalLengthMm", "wavelengthNm", "dpi",
            "targetOffsetXmm", "targetOffsetYmm", "maskType", "polarity");

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;

    @Autowired
    public OpenAiExperimentCopilotProvider(
            ObjectMapper mapper,
            @Value("${fresnel.copilot.openai.enabled:${FRESNEL_COPILOT_OPENAI_ENABLED:true}}") boolean enabled,
            @Value("${fresnel.copilot.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${fresnel.copilot.openai.endpoint:${OPENAI_COPILOT_ENDPOINT:https://api.openai.com/v1/responses}}") String endpoint,
            @Value("${fresnel.copilot.openai.model:${OPENAI_COPILOT_MODEL:gpt-5.6}}") String model,
            @Value("${fresnel.copilot.openai.timeout-seconds:${OPENAI_COPILOT_TIMEOUT_SECONDS:60}}") long timeoutSeconds) {
        this(mapper, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .build(),
                enabled, apiKey, endpoint, model, timeoutSeconds);
    }

    private OpenAiExperimentCopilotProvider(
            ObjectMapper mapper,
            HttpClient httpClient,
            boolean enabled,
            String apiKey,
            String endpoint,
            String model,
            long timeoutSeconds) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = URI.create(endpoint == null || endpoint.isBlank()
                ? "https://api.openai.com/v1/responses" : endpoint.trim());
        this.model = model == null || model.isBlank() ? "gpt-5.6" : model.trim();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    static OpenAiExperimentCopilotProvider forTesting(
            ObjectMapper mapper,
            HttpClient client,
            String apiKey,
            URI endpoint,
            String model) {
        return new OpenAiExperimentCopilotProvider(
                mapper, client, true, apiKey, endpoint.toString(), model, 10);
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String displayName() {
        return "OpenAI structured proposal";
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public boolean available() {
        return enabled && !apiKey.isBlank();
    }

    @Override
    public ExperimentProposal propose(ExperimentCopilotContext context) {
        if (!available()) {
            throw new CopilotProviderException(
                    "PROVIDER_NOT_CONFIGURED",
                    "The OpenAI copilot provider is disabled or OPENAI_API_KEY is not configured.");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("store", false);
        requestBody.put("reasoning", Map.of("effort", "low"));
        requestBody.put("max_output_tokens", 4000);
        requestBody.put("instructions", """
                Translate the user's optical intent into a restricted Fresnel Zone Plate proposal.
                Use only the parameter paths permitted by the supplied schema. Do not invent plugins,
                code, commands, files or URLs. Mark values explicitly stated by the user as
                USER_SUPPLIED and values you infer as COPILOT_INFERRED. Ask a clarification question
                when wavelength or focal distance cannot be determined. Generated prose is advisory;
                Fresnel's deterministic validation remains authoritative.
                """);
        requestBody.put("input", providerInput(context));
        requestBody.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "fresnel_experiment_proposal",
                "strict", true,
                "schema", structuredOutputSchema())));

        final String requestJson;
        try {
            requestJson = mapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new CopilotProviderException(
                    "REQUEST_SERIALIZATION_FAILED",
                    "Could not prepare the OpenAI structured-output request.", e);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        final HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CopilotProviderException(
                    "PROVIDER_INTERRUPTED",
                    "The OpenAI proposal request was interrupted.", e);
        } catch (IOException | RuntimeException e) {
            throw new CopilotProviderException(
                    "PROVIDER_UNREACHABLE",
                    "The OpenAI proposal service could not be reached.", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw classifyHttpFailure(response.statusCode());
        }

        try {
            JsonNode responseJson = mapper.readTree(response.body());
            String outputText = extractOutputText(responseJson);
            StructuredProposal structured = mapper.convertValue(
                    mapper.readTree(outputText), StructuredProposal.class);
            return structured.toProposal(mapper);
        } catch (CopilotProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new CopilotProviderException(
                    "MALFORMED_PROVIDER_RESPONSE",
                    "OpenAI returned a response that did not match the experiment proposal contract.", e);
        }
    }

    private static String providerInput(ExperimentCopilotContext context) {
        String current = context.currentParameters() == null
                ? "{}"
                : context.currentParameters().toString();
        String defaults = context.defaults() == null ? "{}" : context.defaults().toString();
        return "User request:\n" + context.request()
                + "\n\nCurrent user-edited parameters (retain unless the request changes them):\n" + current
                + "\n\nCurrent Fresnel defaults:\n" + defaults
                + "\n\nCurrent Zone Plate parameter schema:\n" + context.parameterSchema();
    }

    private static CopilotProviderException classifyHttpFailure(int status) {
        String code = switch (status) {
            case 401, 403 -> "AUTHENTICATION_FAILED";
            case 429 -> "QUOTA_OR_RATE_LIMIT";
            default -> status >= 500 ? "PROVIDER_UNAVAILABLE" : "PROVIDER_REJECTED_REQUEST";
        };
        String message = switch (status) {
            case 401, 403 -> "OpenAI rejected the configured API credentials.";
            case 429 -> "OpenAI quota or rate limits currently prevent a proposal.";
            default -> status >= 500
                    ? "OpenAI is temporarily unavailable."
                    : "OpenAI rejected the structured proposal request.";
        };
        // Never include the upstream body: it can contain request details and is not
        // needed by clients. The classified status is sufficient and secret-safe.
        return new CopilotProviderException(code, message + " (HTTP " + status + ")");
    }

    private static String extractOutputText(JsonNode response) {
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            throw new CopilotProviderException(
                    "MALFORMED_PROVIDER_RESPONSE",
                    "OpenAI response did not contain an output array.");
        }
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText())) continue;
            JsonNode content = item.path("content");
            if (!content.isArray()) continue;
            for (JsonNode part : content) {
                if ("output_text".equals(part.path("type").asText())
                        && part.path("text").isTextual()) {
                    return part.path("text").asText();
                }
                if ("refusal".equals(part.path("type").asText())) {
                    throw new CopilotProviderException(
                            "PROVIDER_REFUSAL",
                            "OpenAI declined to create an experiment proposal.");
                }
            }
        }
        throw new CopilotProviderException(
                "MALFORMED_PROVIDER_RESPONSE",
                "OpenAI response did not contain structured output text.");
    }

    private static Map<String, Object> structuredOutputSchema() {
        Map<String, Object> valueSchema = Map.of(
                "anyOf", List.of(
                        Map.of("type", "number"),
                        Map.of("type", "string"),
                        Map.of("type", "null")));
        Map<String, Object> pathSchema = Map.of(
                "type", "string",
                "enum", PARAMETER_PATHS);
        Map<String, Object> parameterSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("path", "value", "source", "rationale"),
                "properties", Map.of(
                        "path", pathSchema,
                        "value", valueSchema,
                        "source", Map.of("type", "string", "enum", List.of(
                                "USER_SUPPLIED", "COPILOT_INFERRED")),
                        "rationale", Map.of("type", "string")));
        Map<String, Object> overrideSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("path", "value"),
                "properties", Map.of(
                        "path", pathSchema,
                        "value", valueSchema));
        Map<String, Object> alternativeSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("label", "description", "parameterOverrides"),
                "properties", Map.of(
                        "label", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "parameterOverrides", Map.of(
                                "type", "array",
                                "items", overrideSchema)));
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of(
                        "selectedPluginId", "parameters", "unresolvedQuestions", "alternatives", "summary"),
                "properties", Map.of(
                        "selectedPluginId", Map.of("type", "string", "enum", List.of("zone-plate")),
                        "parameters", Map.of("type", "array", "items", parameterSchema),
                        "unresolvedQuestions", Map.of("type", "array", "items", Map.of("type", "string")),
                        "alternatives", Map.of("type", "array", "items", alternativeSchema),
                        "summary", Map.of("type", "string")));
    }

    private record StructuredProposal(
            String selectedPluginId,
            List<StructuredParameter> parameters,
            List<String> unresolvedQuestions,
            List<StructuredAlternative> alternatives,
            String summary) {

        ExperimentProposal toProposal(ObjectMapper mapper) {
            List<ExperimentProposal.Parameter> mappedParameters = parameters == null
                    ? List.of()
                    : parameters.stream().map(StructuredParameter::toParameter).toList();
            List<ExperimentProposal.Alternative> mappedAlternatives = alternatives == null
                    ? List.of()
                    : alternatives.stream().map(item -> item.toAlternative(mapper)).toList();
            return new ExperimentProposal(
                    selectedPluginId,
                    mappedParameters,
                    unresolvedQuestions,
                    mappedAlternatives,
                    summary);
        }
    }

    private record StructuredParameter(
            String path,
            JsonNode value,
            ExperimentProposal.ValueSource source,
            String rationale) {
        ExperimentProposal.Parameter toParameter() {
            return new ExperimentProposal.Parameter(path, value, source, rationale);
        }
    }

    private record StructuredOverride(String path, JsonNode value) {}

    private record StructuredAlternative(
            String label,
            String description,
            List<StructuredOverride> parameterOverrides) {
        ExperimentProposal.Alternative toAlternative(ObjectMapper mapper) {
            LinkedHashMap<String, JsonNode> overrides = new LinkedHashMap<>();
            if (parameterOverrides != null) {
                for (StructuredOverride override : parameterOverrides) {
                    if (override == null || override.path() == null
                            || override.value() == null || override.value().isNull()) {
                        continue;
                    }
                    if (overrides.putIfAbsent(override.path(), override.value().deepCopy()) != null) {
                        throw new CopilotProviderException(
                                "MALFORMED_PROVIDER_RESPONSE",
                                "OpenAI returned the same alternative parameter more than once.");
                    }
                }
            }
            return new ExperimentProposal.Alternative(
                    label,
                    description,
                    mapper.valueToTree(overrides));
        }
    }
}
