package org.fresnel.backend.api;

import java.io.IOException;

/** Destination abstraction used by {@link FresnelJobExecutor}. */
@FunctionalInterface
public interface FresnelJobOutputSink {

    /**
     * Stores one completed artifact. Implementations must treat the supplied
     * filename as untrusted even though the canonical job parser already requires
     * portable basenames.
     */
    void write(GeneratedArtifact artifact, byte[] content) throws IOException;
}
