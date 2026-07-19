package org.fresnel.backend.copilot;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Restricted, data-only proposal returned by an experiment-copilot provider.
 *
 * <p>A provider may suggest parameters and explanations, but this object is not a
 * Fresnel job and cannot invoke renderers, files or commands. The backend grounding
 * service must validate every path and value through the canonical Fresnel contracts
 * before a job can be produced.</p>
 */
public record ExperimentProposal(
        String selectedPluginId,
        List<Parameter> parameters,
        List<String> unresolvedQuestions,
        List<Alternative> alternatives,
        String summary
) {

    public ExperimentProposal {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        summary = summary == null ? "" : summary.trim();
    }

    /** How a value entered the proposal. */
    public enum ValueSource {
        USER_SUPPLIED,
        COPILOT_INFERRED,
        FRESNEL_DEFAULT
    }

    /** One proposed top-level plugin parameter with field-level rationale. */
    public record Parameter(
            String path,
            JsonNode value,
            ValueSource source,
            String rationale
    ) {
        public Parameter {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("proposal parameter path must not be blank");
            }
            path = path.trim();
            value = value == null ? null : value.deepCopy();
            source = source == null ? ValueSource.COPILOT_INFERRED : source;
            rationale = rationale == null ? "" : rationale.trim();
        }
    }

    /** A reviewable alternative, expressed only as bounded parameter overrides. */
    public record Alternative(
            String label,
            String description,
            JsonNode parameterOverrides
    ) {
        public Alternative {
            label = label == null ? "Alternative" : label.trim();
            description = description == null ? "" : description.trim();
            parameterOverrides = parameterOverrides == null ? null : parameterOverrides.deepCopy();
        }
    }
}
