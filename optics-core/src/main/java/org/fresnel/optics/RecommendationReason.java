package org.fresnel.optics;

/**
 * One scoring dimension that contributed to a candidate design's ranking.
 *
 * @param dimension   scoring dimension identifier (e.g. {@code "printability"})
 * @param description human-readable note about how this candidate performed on this dimension
 */
public record RecommendationReason(String dimension, String description) {}
