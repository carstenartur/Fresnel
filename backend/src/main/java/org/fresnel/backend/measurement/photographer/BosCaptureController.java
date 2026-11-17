package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.CaptureProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/experiments/bos-captures")
@ConditionalOnProperty(name = "fresnel.capture.photographer.enabled", havingValue = "true")
class BosCaptureController {
    private final BosExperiments experiments;
    BosCaptureController(BosExperiments experiments) { this.experiments = experiments; }

    record Selection(String cameraId) {}
    record Acknowledgement(boolean ready) {}

    @GetMapping("/cameras")
    ResponseEntity<List<BosExperiments.Camera>> cameras() { return ok(experiments.cameras()); }

    @GetMapping
    ResponseEntity<List<BosExperiments.View>> list(Principal caller) { return ok(experiments.list(caller.getName())); }

    @PutMapping("/{id}")
    ResponseEntity<BosExperiments.View> create(Principal caller, @PathVariable("id") UUID id, @RequestBody Selection body) {
        return ok(experiments.create(caller.getName(), id, body.cameraId()));
    }

    @GetMapping("/{id}")
    ResponseEntity<BosExperiments.View> read(Principal caller, @PathVariable("id") UUID id) {
        return ok(experiments.get(caller.getName(), id));
    }

    @PostMapping("/{id}/steps/{step}")
    ResponseEntity<BosExperiments.View> confirm(Principal caller, @PathVariable("id") UUID id, @PathVariable("step") String step,
                                                 @RequestBody Acknowledgement confirmation) {
        if (!confirmation.ready()) throw new PhotographerPairing.Failure(400, "STEP_ACKNOWLEDGEMENT_REQUIRED");
        return ok(experiments.confirm(caller.getName(), id, step));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<BosExperiments.View> cancel(Principal caller, @PathVariable("id") UUID id) {
        return ok(experiments.cancel(caller.getName(), id));
    }

    @GetMapping("/{id}/images/{step}")
    ResponseEntity<FileSystemResource> image(Principal caller, @PathVariable("id") UUID id, @PathVariable("step") String step) {
        var file = experiments.source(caller.getName(), id, step);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.IMAGE_PNG).body(new FileSystemResource(file));
    }

    @ExceptionHandler(PhotographerPairing.Failure.class)
    ResponseEntity<?> failure(PhotographerPairing.Failure error) { return problem(error.status(), error.getMessage()); }

    @ExceptionHandler(CaptureProviderException.class)
    ResponseEntity<?> failure(CaptureProviderException error) {
        int status = switch (error.code()) {
            case CONFLICT, DEVICE_UNAVAILABLE, INVALID_PLAN -> 409;
            case UNAVAILABLE, TRANSIENT_IO -> 503;
            default -> 502;
        };
        return problem(status, error.code().name());
    }

    private static ResponseEntity<Map<String, String>> problem(int status, String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(Map.of("code", code));
    }
    private static <T> ResponseEntity<T> ok(T result) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(result);
    }
}
