package org.fresnel.backend.api;

import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

/** Request for a grounded natural-language experiment proposal. */
public record ExperimentCopilotRequest(
        @NotBlank String request,
        String provider,
        JsonNode currentParameters
) {
    public String resolvedProvider() {
        return provider == null || provider.isBlank() ? "mock" : provider.trim();
    }
}
