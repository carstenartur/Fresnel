package org.fresnel.backend.api.photometry;

import org.fresnel.measurement.photometry.DisplayGeometry;
import org.fresnel.measurement.photometry.DisplayIlluminationSequenceGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Stateless pattern generation; user images remain in the operator's browser. */
@RestController
@RequestMapping("/api/measurements/display-photometric-stereo")
public class DisplayPhotometryController {
    public static final Set<String> FIELDS = Arrays.stream(DisplayGeometry.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toUnmodifiableSet());
    private final ObjectMapper mapper;
    private final Semaphore permits = new Semaphore(2);
    public DisplayPhotometryController(ObjectMapper mapper) { this.mapper = mapper; }

    @PostMapping("/sequence")
    public DisplayIlluminationSequenceGenerator.Manifest sequence(@RequestBody JsonNode supplied) {
        var settings = geometry(supplied);
        return bounded(() -> DisplayIlluminationSequenceGenerator.generate(settings));
    }

    @PostMapping(value = "/patterns/{id}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> pattern(@PathVariable("id") String id, @RequestBody JsonNode supplied) {
        var settings = geometry(supplied);
        return bounded(() -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .body(DisplayIlluminationSequenceGenerator.png(settings, id)));
    }

    private DisplayGeometry geometry(JsonNode input) {
        if (!input.isObject() || input.size() != FIELDS.size())
            throw new IllegalArgumentException("Supply exactly the display geometry fields from the plugin schema");
        for (var key : FIELDS) if (!input.path(key).isNumber())
            throw new IllegalArgumentException("Geometry fields must be numbers");
        for (var key : Set.of("widthPx", "heightPx", "lightCount", "settleDelayMs"))
            if (!input.get(key).isIntegralNumber()) throw new IllegalArgumentException(key + " must be an integer");
        return mapper.convertValue(input, DisplayGeometry.class);
    }
    private <T> T bounded(Supplier<T> action) {
        if (!permits.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Display generator busy");
        try { return action.get(); }
        finally { permits.release(); }
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(error.getMessage());
    }
}
