package org.fresnel.optics;

/**
 * Stability classification for a Fresnel plugin.
 *
 * <p>Stability levels communicate the maturity and expected API-stability of a
 * plugin to both the frontend and downstream tooling.
 */
public enum PluginStabilityLevel {

    /**
     * Plugin is production-ready.  Its rendering logic, parameter record and
     * export behaviour are stable and will not change incompatibly across minor
     * releases.
     */
    STABLE,

    /**
     * Plugin is feature-complete but may still receive breaking parameter or
     * output changes while gathering user feedback.
     */
    BETA,

    /**
     * Plugin is under active development.  Its interface should be considered
     * unstable and is subject to change at any time.
     */
    EXPERIMENTAL,
}
