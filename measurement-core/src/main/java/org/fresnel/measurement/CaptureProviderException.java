package org.fresnel.measurement;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Typed provider failure that can cross adapter boundaries without leaking
 * provider exception messages, credentials or private camera addresses.
 */
public final class CaptureProviderException extends RuntimeException {

    private static final Pattern MESSAGE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9._-]*");

    private final CaptureProvider.FailureCode code;
    private final boolean retryable;
    private final String messageCode;

    public CaptureProviderException(
            CaptureProvider.FailureCode code,
            boolean retryable,
            String messageCode) {
        this(code, retryable, messageCode, null);
    }

    public CaptureProviderException(
            CaptureProvider.FailureCode code,
            boolean retryable,
            String messageCode,
            Throwable cause) {
        super(normalizeMessageCode(messageCode), cause);
        this.code = Objects.requireNonNull(code, "code");
        this.retryable = retryable;
        this.messageCode = normalizeMessageCode(messageCode);
    }

    public CaptureProvider.FailureCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public String messageCode() {
        return messageCode;
    }

    public CaptureProvider.Failure toFailure() {
        return new CaptureProvider.Failure(code, retryable, messageCode);
    }

    private static String normalizeMessageCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("messageCode must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 96 || !MESSAGE_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("messageCode is invalid");
        }
        return normalized;
    }
}
