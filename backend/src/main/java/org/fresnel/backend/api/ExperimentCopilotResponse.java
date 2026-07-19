package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.fresnel.backend.copilot.ExperimentProposal;
import org.fresnel.optics.DesignValidationReport;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** Complete review model for a grounded experiment proposal. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentCopilotResponse(
        String providerId,
        String modelId,
        String selectedPluginId,
        int parameterSchemaVersion,
        String summary,
        boolean ready,
        List<GroundedParameter> parameters,
        List<String> unresolvedQuestions,
        List<ExperimentProposal.Alternative> alternatives,
        JsonNode normalizedParameters,
        DesignValidationReport validation,
        FresnelJobDocument job
) {
    public ExperimentCopilotResponse {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        normalizedParameters = normalizedParameters == null ? null : normalizedParameters.deepCopy();
    }

    /** Final parameter value plus reviewable origin and schema default. */
    public record GroundedParameter(
            String path,
            JsonNode value,
            JsonNode defaultValue,
            ExperimentProposal.ValueSource source,
            String rationale
    ) {
        public GroundedParameter {
            value = value == null ? null : value.deepCopy();
            defaultValue = defaultValue == null ? null : defaultValue.deepCopy();
            rationale = rationale == null ? "" : rationale;
        }
    }
}
