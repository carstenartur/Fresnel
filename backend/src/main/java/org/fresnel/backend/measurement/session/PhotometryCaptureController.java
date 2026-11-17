package org.fresnel.backend.measurement.session;

import org.fresnel.backend.measurement.photographer.PhotographerPairing;
import org.fresnel.measurement.CaptureProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/experiments/photometry-captures")
@ConditionalOnProperty(name="fresnel.capture.photographer.enabled", havingValue="true")
class PhotometryCaptureController {
    private final PhotometryCapture capture;
    PhotometryCaptureController(PhotometryCapture capture) { this.capture = capture; }

    @GetMapping("/cameras") List<PhotometryCapture.Camera> cameras() { return capture.cameras(); }
    @GetMapping List<PhotometryCapture.View> list(Principal p) throws IOException { return capture.list(p.getName()); }
    @GetMapping("/{id}") PhotometryCapture.View get(Principal p, @PathVariable("id") UUID id) throws IOException {
        return capture.get(p.getName(), id);
    }
    @PutMapping("/{id}") PhotometryCapture.View create(Principal p, @PathVariable("id") UUID id,
            @RequestBody PhotometryCapture.Selection selection) { return capture.create(p.getName(),id,selection); }

    @PostMapping("/{id}/steps/{step}/trigger")
    PhotometryCapture.View trigger(Principal p, @PathVariable("id") UUID id, @PathVariable("step") String step,
            @RequestBody PhotometryCapture.Presentation ack) { return capture.trigger(p.getName(),id,step,ack); }

    @PostMapping("/{id}/steps/{step}/accept")
    PhotometryCapture.View accept(Principal p, @PathVariable("id") UUID id, @PathVariable("step") String step,
            @RequestBody PhotometryCapture.Presentation ack) { return capture.accept(p.getName(),id,step,ack); }

    @DeleteMapping("/{id}") PhotometryCapture.View cancel(Principal p, @PathVariable("id") UUID id) {
        return capture.cancel(p.getName(),id);
    }
    @ExceptionHandler(PhotographerPairing.Failure.class)
    ResponseEntity<?> pairing(PhotographerPairing.Failure e) {
        return ResponseEntity.status(e.status()).body(Map.of("code",e.getMessage()));
    }
    @ExceptionHandler(CaptureProviderException.class)
    ResponseEntity<?> provider(CaptureProviderException e) {
        int status = switch (e.code()) {
            case INVALID_PLAN, CONFLICT, DEVICE_UNAVAILABLE -> 409;
            case TRANSIENT_IO, UNAVAILABLE -> 503;
            default -> 502;
        };
        return ResponseEntity.status(status).body(Map.of("code",e.code().name()));
    }
}
