package org.fresnel.backend.copilot;

import tools.jackson.databind.JsonNode;

/** Only the bounded schema context exposed to an experiment-copilot provider. */
public record ExperimentCopilotContext(
        String request,
        JsonNode parameterSchema,
        JsonNode defaults,
        JsonNode currentParameters
) {
    public ExperimentCopilotContext {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("experiment request must not be blank");
        }
        request = request.trim();
        parameterSchema = parameterSchema == null ? null : parameterSchema.deepCopy();
        defaults = defaults == null ? null : defaults.deepCopy();
        currentParameters = currentParameters == null ? null : currentParameters.deepCopy();
    }
}
