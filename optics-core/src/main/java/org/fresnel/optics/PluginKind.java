package org.fresnel.optics;

/**
 * Top-level interaction model of a trusted Fresnel plugin.
 *
 * <p>Design plugins render deterministic optical elements. Measurement plugins
 * orchestrate targets, capture sets and analysis through durable experiment
 * sessions. The discriminator is public metadata; it never selects a remotely
 * supplied implementation class.</p>
 */
public enum PluginKind {
    DESIGN,
    MEASUREMENT
}
