package org.fresnel.backend.copilot;

import org.fresnel.backend.api.ExperimentCopilotProviderStatus;
import org.fresnel.backend.api.ExperimentCopilotRequest;
import org.fresnel.backend.api.ExperimentCopilotResponse;
import org.fresnel.backend.api.FresnelJobDocument;
import org.fresnel.backend.api.FresnelJobService;
import org.fresnel.backend.api.PluginSchemaDocument;
import org.fresnel.backend.api.PluginSchemaService;
import org.fresnel.backend.api.SingleZonePlateRequest;
import org.fresnel.optics.DesignAssistant;
import org.fresnel.optics.DesignGoal;
import org.fresnel.optics.DesignValidationReport;
import org.fresnel.optics.DesignValidationReports;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trust boundary between generated proposals and deterministic Fresnel services.
 */
@Service
public final class ExperimentCopilotService {

    private static final String MVP_PLUGIN_ID = "zone-plate";
    private static final Set<String> CORE_INTENT_FIELDS = Set.of("wavelengthNm", "focalLengthMm");

    private final Map<String, ExperimentCopilotProvider> providers;
    private final FresnelJobService jobService;
    private final PluginSchemaService schemaService;
    private final ObjectMapper mapper;

    public ExperimentCopilotService(
            List<ExperimentCopilotProvider> providers,
            FresnelJobService jobService,
            PluginSchemaService schemaService,
            ObjectMapper mapper) {
        LinkedHashMap<String, ExperimentCopilotProvider> indexed = new LinkedHashMap<>();
        for (ExperimentCopilotProvider provider : providers) {
            ExperimentCopilotProvider previous = indexed.put(provider.id(), provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate experiment copilot provider id: " + provider.id());
            }
        }
        this.providers = Map.copyOf(indexed);
        this.jobService = jobService;
        this.schemaService = schemaService;
        this.mapper = mapper;
    }

    public List<ExperimentCopilotProviderStatus> providerStatuses() {
        return providers.values().stream()
                .map(provider -> new ExperimentCopilotProviderStatus(
                        provider.id(),
                        provider.displayName(),
                        provider.modelId(),
                        provider.available()))
                .toList();
    }

    public ExperimentCopilotResponse propose(ExperimentCopilotRequest request) {
        ExperimentCopilotProvider provider = providers.get(request.resolvedProvider());
        if (provider == null) {
            throw new IllegalArgumentException("unknown copilot provider: " + request.resolvedProvider());
        }
        if (!provider.available()) {
            throw new CopilotProviderException(
                    "PROVIDER_NOT_CONFIGURED",
                    "Copilot provider '" + provider.id() + "' is not configured.");
        }

        PluginSchemaDocument schema = schemaService.requireByPluginId(MVP_PLUGIN_ID);
        ExperimentProposal proposal = provider.propose(new ExperimentCopilotContext(
                request.request(),
                schema.parameterSchema(),
                schema.defaults(),
                request.currentParameters()));
        if (!MVP_PLUGIN_ID.equals(proposal.selectedPluginId())) {
            throw new IllegalArgumentException(
                    "The first experiment-copilot iteration supports only plugin " + MVP_PLUGIN_ID);
        }

        LinkedHashSet<String> allowedFields = fieldNames(schema.parameterSchema().path("properties"));
        LinkedHashMap<String, JsonNode> values = new LinkedHashMap<>();
        LinkedHashMap<String, ExperimentProposal.ValueSource> sources = new LinkedHashMap<>();
        LinkedHashMap<String, String> rationales = new LinkedHashMap<>();
        LinkedHashMap<String, JsonNode> defaults = new LinkedHashMap<>();

        for (Map.Entry<String, JsonNode> entry : schema.defaults().properties()) {
            defaults.put(entry.getKey(), entry.getValue().deepCopy());
            values.put(entry.getKey(), entry.getValue().deepCopy());
            sources.put(entry.getKey(), ExperimentProposal.ValueSource.FRESNEL_DEFAULT);
            rationales.put(entry.getKey(), "Current Fresnel parameter-schema default.");
        }

        overlayCurrentParameters(request.currentParameters(), allowedFields, values, sources, rationales);

        Set<String> proposedPaths = new LinkedHashSet<>();
        for (ExperimentProposal.Parameter parameter : proposal.parameters()) {
            if (!allowedFields.contains(parameter.path())) {
                throw new IllegalArgumentException(
                        "copilot proposed an unknown parameter path: " + parameter.path());
            }
            if (!proposedPaths.add(parameter.path())) {
                throw new IllegalArgumentException(
                        "copilot proposed parameter more than once: " + parameter.path());
            }
            if (parameter.value() == null || parameter.value().isNull()) {
                continue;
            }
            values.put(parameter.path(), parameter.value().deepCopy());
            sources.put(parameter.path(), parameter.source());
            rationales.put(parameter.path(), parameter.rationale());
        }

        LinkedHashSet<String> questions = new LinkedHashSet<>(proposal.unresolvedQuestions());
        for (String coreField : CORE_INTENT_FIELDS) {
            if (sources.get(coreField) == ExperimentProposal.ValueSource.FRESNEL_DEFAULT) {
                questions.add(switch (coreField) {
                    case "wavelengthNm" -> "Which design wavelength should Fresnel use, in nanometres?";
                    case "focalLengthMm" -> "What focal distance should the design target?";
                    default -> "Which value should Fresnel use for " + coreField + "?";
                });
            }
        }

        if (questions.isEmpty()
                && sources.get("apertureDiameterMm") == ExperimentProposal.ValueSource.FRESNEL_DEFAULT) {
            inferPrintableAperture(values, sources, rationales);
        }

        List<ExperimentProposal.Alternative> alternatives = validateAlternatives(
                proposal.alternatives(), allowedFields);
        List<ExperimentCopilotResponse.GroundedParameter> grounded = groundedParameters(
                allowedFields, values, defaults, sources, rationales);

        if (!questions.isEmpty()) {
            return new ExperimentCopilotResponse(
                    provider.id(),
                    provider.modelId(),
                    MVP_PLUGIN_ID,
                    schema.parameterSchemaVersion(),
                    proposal.summary(),
                    false,
                    grounded,
                    List.copyOf(questions),
                    alternatives,
                    null,
                    null,
                    null);
        }

        JsonNode rawParameters = mapper.valueToTree(values);
        FresnelJobDocument candidate = new FresnelJobDocument(
                FresnelJobDocument.SCHEMA_URL,
                FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION,
                new FresnelJobDocument.PluginRef(
                        MVP_PLUGIN_ID,
                        schema.parameterSchemaVersion(),
                        MVP_PLUGIN_ID + "/1"),
                rawParameters,
                null,
                new FresnelJobDocument.Provenance(
                        "Fresnel experiment copilot (" + provider.id() + ")",
                        null,
                        null));

        FresnelJobDocument normalized = jobService.normalize(candidate);
        SingleZonePlateRequest zonePlate = mapper.convertValue(
                normalized.parameters(), SingleZonePlateRequest.class);
        DesignValidationReport validation = DesignValidationReports.forZonePlate(
                zonePlate.toParameters());

        return new ExperimentCopilotResponse(
                provider.id(),
                provider.modelId(),
                MVP_PLUGIN_ID,
                schema.parameterSchemaVersion(),
                proposal.summary(),
                true,
                groundedParameters(
                        allowedFields,
                        toNodeMap(normalized.parameters()),
                        defaults,
                        sources,
                        rationales),
                List.of(),
                alternatives,
                normalized.parameters(),
                validation,
                normalized);
    }

    private void overlayCurrentParameters(
            JsonNode currentParameters,
            Set<String> allowedFields,
            Map<String, JsonNode> values,
            Map<String, ExperimentProposal.ValueSource> sources,
            Map<String, String> rationales) {
        if (currentParameters == null || currentParameters.isNull()) return;
        if (!currentParameters.isObject()) {
            throw new IllegalArgumentException("currentParameters must be a JSON object");
        }
        for (Map.Entry<String, JsonNode> entry : currentParameters.properties()) {
            if (!allowedFields.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "currentParameters contains unknown field: " + entry.getKey());
            }
            values.put(entry.getKey(), entry.getValue().deepCopy());
            sources.put(entry.getKey(), ExperimentProposal.ValueSource.USER_SUPPLIED);
            rationales.put(entry.getKey(), "Retained from the current user-edited Fresnel job.");
        }
    }

    private static LinkedHashSet<String> fieldNames(JsonNode properties) {
        if (!properties.isObject()) {
            throw new IllegalStateException("Zone Plate parameter schema has no properties object");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            result.add(entry.getKey());
        }
        return result;
    }

    private void inferPrintableAperture(
            Map<String, JsonNode> values,
            Map<String, ExperimentProposal.ValueSource> sources,
            Map<String, String> rationales) {
        double dpi = requiredNumber(values, "dpi");
        double wavelength = requiredNumber(values, "wavelengthNm");
        double focalLength = requiredNumber(values, "focalLengthMm");
        double aperture = DesignAssistant.recommend(new DesignGoal(
                        dpi,
                        210.0,
                        297.0,
                        wavelength,
                        focalLength,
                        null))
                .recommended()
                .parameters()
                .apertureDiameterMm();
        values.put("apertureDiameterMm", mapper.valueToTree(aperture));
        sources.put("apertureDiameterMm", ExperimentProposal.ValueSource.COPILOT_INFERRED);
        rationales.put(
                "apertureDiameterMm",
                "Selected by Fresnel's deterministic DesignAssistant for a printable A4 baseline; "
                        + "the generated model did not choose this value.");
    }

    private static double requiredNumber(Map<String, JsonNode> values, String field) {
        JsonNode value = values.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("parameter " + field + " must be numeric");
        }
        return value.doubleValue();
    }

    private static List<ExperimentProposal.Alternative> validateAlternatives(
            List<ExperimentProposal.Alternative> alternatives,
            Set<String> allowedFields) {
        List<ExperimentProposal.Alternative> safe = new ArrayList<>();
        for (ExperimentProposal.Alternative alternative : alternatives) {
            JsonNode overrides = alternative.parameterOverrides();
            if (overrides != null && !overrides.isObject()) {
                throw new IllegalArgumentException("copilot alternative overrides must be an object");
            }
            if (overrides != null) {
                for (Map.Entry<String, JsonNode> entry : overrides.properties()) {
                    if (!allowedFields.contains(entry.getKey())) {
                        throw new IllegalArgumentException(
                                "copilot alternative proposed an unknown parameter path: " + entry.getKey());
                    }
                }
            }
            safe.add(alternative);
        }
        return List.copyOf(safe);
    }

    private static List<ExperimentCopilotResponse.GroundedParameter> groundedParameters(
            Set<String> fieldOrder,
            Map<String, JsonNode> values,
            Map<String, JsonNode> defaults,
            Map<String, ExperimentProposal.ValueSource> sources,
            Map<String, String> rationales) {
        List<ExperimentCopilotResponse.GroundedParameter> result = new ArrayList<>();
        for (String field : fieldOrder) {
            result.add(new ExperimentCopilotResponse.GroundedParameter(
                    field,
                    values.get(field),
                    defaults.get(field),
                    sources.getOrDefault(field, ExperimentProposal.ValueSource.FRESNEL_DEFAULT),
                    rationales.getOrDefault(field, "Current Fresnel parameter-schema default.")));
        }
        return List.copyOf(result);
    }

    private static LinkedHashMap<String, JsonNode> toNodeMap(JsonNode object) {
        LinkedHashMap<String, JsonNode> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            result.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return result;
    }
}
