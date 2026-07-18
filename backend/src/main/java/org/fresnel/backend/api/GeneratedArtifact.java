package org.fresnel.backend.api;

/**
 * Reproducibility metadata for one artifact produced from a canonical
 * {@link FresnelJobDocument} output request.
 *
 * <p>The normalized hash is format-aware: PNG files hash decoded pixels together
 * with dimensions and physical resolution, text formats hash normalized UTF-8 line
 * endings, and opaque binary formats currently hash their bytes.</p>
 */
public record GeneratedArtifact(
        String outputId,
        String filename,
        String mediaType,
        long sizeBytes,
        String normalizedSha256,
        Integer widthPx,
        Integer heightPx,
        Double dpi
) {
    public GeneratedArtifact {
        if (outputId == null || outputId.isBlank()) {
            throw new IllegalArgumentException("generated artifact outputId must not be empty");
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("generated artifact filename must not be empty");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("generated artifact mediaType must not be empty");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("generated artifact size must not be negative");
        }
        if (normalizedSha256 == null || !normalizedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("generated artifact hash must be lowercase SHA-256");
        }
    }
}
