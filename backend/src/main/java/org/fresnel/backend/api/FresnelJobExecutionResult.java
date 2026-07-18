package org.fresnel.backend.api;

import java.util.List;

/** Canonical job plus metadata for every generated production artifact. */
public record FresnelJobExecutionResult(
        FresnelJobDocument job,
        List<GeneratedArtifact> artifacts
) {
    public FresnelJobExecutionResult {
        if (job == null) throw new IllegalArgumentException("executed job must not be null");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
