package org.fresnel.backend.docs;

import java.util.List;

/** Machine-readable trace from documentation jobs to their generated artifacts. */
public record FresnelDocumentationManifest(
        int formatVersion,
        List<Example> examples
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public FresnelDocumentationManifest {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported documentation manifest version: " + formatVersion);
        }
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    public record Example(
            String id,
            String pluginId,
            String job,
            int jobFormatVersion,
            int parameterSchemaVersion,
            String algorithmVersion,
            String parameterSha256,
            List<Artifact> artifacts
    ) {
        public Example {
            requireText(id, "example id");
            requireText(pluginId, "example plugin id");
            requireText(job, "example job path");
            requireText(algorithmVersion, "example algorithm version");
            if (jobFormatVersion < 1) {
                throw new IllegalArgumentException("example job format version must be positive");
            }
            if (parameterSchemaVersion < 1) {
                throw new IllegalArgumentException(
                        "example parameter schema version must be positive");
            }
            if (parameterSha256 == null || !parameterSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "example parameter hash must be lowercase SHA-256");
            }
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            if (artifacts.isEmpty()) {
                throw new IllegalArgumentException(
                        "documentation example must contain at least one artifact");
            }
        }
    }

    public record Artifact(
            String id,
            String path,
            String mediaType,
            long sizeBytes,
            Integer widthPx,
            Integer heightPx,
            Double dpi,
            String normalizedSha256
    ) {
        public Artifact {
            requireText(id, "artifact id");
            requireText(path, "artifact path");
            requireText(mediaType, "artifact media type");
            if (sizeBytes < 1) {
                throw new IllegalArgumentException("artifact size must be positive");
            }
            if (normalizedSha256 == null || !normalizedSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "artifact normalized hash must be lowercase SHA-256");
            }
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
    }
}
