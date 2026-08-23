package org.fresnel.measurement;

/**
 * Durable lifecycle shared by camera-assisted Fresnel measurement plugins.
 *
 * <p>The state is intentionally independent of HTTP, persistence and any
 * concrete capture provider so the same transitions can be used by the backend,
 * tests and future command-line automation.</p>
 */
public enum MeasurementSessionState {
    DRAFT,
    TARGET_READY,
    CALIBRATING,
    READY_TO_CAPTURE,
    CAPTURING,
    READY_TO_ANALYZE,
    ANALYZING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** Returns whether no further normal workflow transition is possible. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
