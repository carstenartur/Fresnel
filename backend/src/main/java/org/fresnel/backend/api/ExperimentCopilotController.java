package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.backend.copilot.CopilotProviderException;
import org.fresnel.backend.copilot.ExperimentCopilotService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Natural-language intent-to-contract API for the grounded experiment copilot. */
@RestController
@RequestMapping("/api/assistant")
public final class ExperimentCopilotController {

    private final ExperimentCopilotService copilotService;

    public ExperimentCopilotController(ExperimentCopilotService copilotService) {
        this.copilotService = copilotService;
    }

    @GetMapping(value = "/providers", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ExperimentCopilotProviderStatus> providers() {
        return copilotService.providerStatuses();
    }

    @PostMapping(
            value = "/propose",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExperimentCopilotResponse propose(@Valid @RequestBody ExperimentCopilotRequest request) {
        return copilotService.propose(request);
    }

    @ExceptionHandler(CopilotProviderException.class)
    public ResponseEntity<CopilotErrorResponse> providerFailure(CopilotProviderException exception) {
        HttpStatus status = "QUOTA_OR_RATE_LIMIT".equals(exception.code())
                ? HttpStatus.TOO_MANY_REQUESTS
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .body(new CopilotErrorResponse(exception.code(), safeMessage(exception)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CopilotErrorResponse> invalidProposal(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new CopilotErrorResponse("INVALID_PROPOSAL", safeMessage(exception)));
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "The experiment proposal could not be processed.";
        String oneLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500);
    }

    public record CopilotErrorResponse(String code, String message) {}
}
