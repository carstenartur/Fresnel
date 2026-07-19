package org.fresnel.backend.api;

/** Public, secret-free availability metadata for one copilot provider. */
public record ExperimentCopilotProviderStatus(
        String id,
        String displayName,
        String modelId,
        boolean available
) {}
