package org.fresnel.optics;

/**
 * A human-readable warning emitted by the Optical Design Assistant.
 *
 * @param code    machine-readable warning code
 * @param message human-readable description
 */
public record AssistantWarning(String code, String message) {}
