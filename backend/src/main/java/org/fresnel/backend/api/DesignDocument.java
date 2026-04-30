package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * Envelope used for save/load of any Fresnel design.
 *
 * <p>The wrapper carries a {@code kind} discriminator (one of {@code single},
 * {@code hex}, {@code foil}, {@code multifocus}, {@code rgb}, {@code hologram}),
 * a schema {@code version} for forward compatibility, and the design's own
 * request payload as a free-form JSON node. This allows clients to round-trip
 * a design as a single JSON file without losing fidelity.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesignDocument(String kind, int version, JsonNode payload) {

    /** Current envelope schema version. Bump when breaking changes are made. */
    public static final int SCHEMA_VERSION = 1;

    public static final String KIND_SINGLE = "single";
    public static final String KIND_HEX = "hex";
    public static final String KIND_FOIL = "foil";
    public static final String KIND_MULTIFOCUS = "multifocus";
    public static final String KIND_RGB = "rgb";
    public static final String KIND_HOLOGRAM = "hologram";

    public static boolean isKnownKind(String k) {
        return KIND_SINGLE.equals(k)
                || KIND_HEX.equals(k)
                || KIND_FOIL.equals(k)
                || KIND_MULTIFOCUS.equals(k)
                || KIND_RGB.equals(k)
                || KIND_HOLOGRAM.equals(k);
    }
}
