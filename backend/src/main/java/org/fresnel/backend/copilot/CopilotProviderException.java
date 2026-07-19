package org.fresnel.backend.copilot;

/** Safe, classified provider failure suitable for an API response. */
public final class CopilotProviderException extends RuntimeException {

    private final String code;

    public CopilotProviderException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "PROVIDER_ERROR" : code;
    }

    public CopilotProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null || code.isBlank() ? "PROVIDER_ERROR" : code;
    }

    public String code() {
        return code;
    }
}
