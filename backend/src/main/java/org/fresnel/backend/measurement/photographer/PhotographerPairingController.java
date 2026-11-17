package org.fresnel.backend.measurement.photographer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Fresnel administrator operations. The service bearer and remote origin never enter the response. */
@RestController
@RequestMapping("/api/admin/capture-providers/photographer/pairing")
@ConditionalOnProperty(name = "fresnel.capture.photographer.enabled", havingValue = "true")
class PhotographerPairingController {
    private final PhotographerPairing pairing;

    PhotographerPairingController(PhotographerPairing pairing) { this.pairing = pairing; }

    record Code(String pairingCode) {
        @Override public String toString() { return "Photographer pairing code"; }
    }

    @GetMapping
    ResponseEntity<PhotographerPairing.Status> status() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(pairing.status());
    }

    @PostMapping(consumes = "application/json")
    ResponseEntity<Void> pair(@RequestBody Code code) {
        pairing.pair(code.pairingCode());
        return noContent();
    }

    @DeleteMapping
    ResponseEntity<Void> forget() {
        pairing.forget();
        return noContent();
    }

    @ExceptionHandler(PhotographerPairing.Failure.class)
    ResponseEntity<Map<String, String>> failure(PhotographerPairing.Failure error) {
        return ResponseEntity.status(error.status()).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(Map.of("code", error.getMessage()));
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }
}
