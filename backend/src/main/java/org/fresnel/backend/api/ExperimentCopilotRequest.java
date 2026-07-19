package org.fresnel.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Request for a grounded natural-language experiment proposal. */
public record ExperimentCopilotRequest(
        @NotBlank
        @Size(max = 8000, message = "experiment request must not exceed 8000 characters")
        String request,
        String provider,
        JsonNode currentParameters
) {
    public String resolvedProvider() {
        return provider == null || provider.isBlank() ? "mock" : provider.trim();
    }
}
