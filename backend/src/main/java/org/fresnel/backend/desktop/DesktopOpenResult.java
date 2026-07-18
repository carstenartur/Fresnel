package org.fresnel.backend.desktop;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.fresnel.backend.api.FresnelJobDocument;

/**
 * Public, one-time result consumed by the browser after a desktop file-open request.
 * Source filesystem paths are deliberately absent from this contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesktopOpenResult(
        boolean valid,
        FresnelJobDocument job,
        String errorCode,
        String errorMessage
) {
    public static DesktopOpenResult valid(FresnelJobDocument job) {
        if (job == null) {
            throw new IllegalArgumentException("Desktop open result job must not be null");
        }
        return new DesktopOpenResult(true, job, null, null);
    }

    public static DesktopOpenResult invalid(String code, String message) {
        String safeCode = code == null || code.isBlank() ? "INVALID_JOB" : code.trim();
        String safeMessage = message == null || message.isBlank()
                ? "The Fresnel job could not be opened."
                : message.trim();
        return new DesktopOpenResult(false, null, safeCode, safeMessage);
    }
}
