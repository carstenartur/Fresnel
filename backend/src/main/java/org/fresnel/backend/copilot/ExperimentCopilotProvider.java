package org.fresnel.backend.copilot;

/** Provider-neutral SPI for translating optical intent into a restricted proposal. */
public interface ExperimentCopilotProvider {

    /** Stable configuration/API identifier. */
    String id();

    /** Human-readable provider label. */
    String displayName();

    /** Model or implementation identifier recorded in provenance. */
    String modelId();

    /** Whether this provider is currently configured for use. */
    boolean available();

    /** Produce a restricted proposal. No rendering, filesystem or command access is allowed. */
    ExperimentProposal propose(ExperimentCopilotContext context);
}
