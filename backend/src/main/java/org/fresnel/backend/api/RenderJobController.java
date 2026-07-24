package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.backend.jobs.RenderJob;
import org.fresnel.backend.jobs.RenderJobService;
import org.fresnel.optics.HexMacroCellRenderer;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.fresnel.optics.WindowFoilRenderer;
import org.fresnel.optics.ZonePlateRenderer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Authenticated asynchronous render-job endpoints.
 *
 * <p>Job identifiers are private identifiers, not public share links. Status,
 * SSE and result reads return 404 for both unknown and unauthorized identifiers,
 * preventing object-existence probing across users.</p>
 */
@RestController
@RequestMapping("/api/jobs")
public class RenderJobController {

    private final RenderJobService jobs;

    public RenderJobController(RenderJobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping(value = "/single",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> submitSingle(@Valid @RequestBody SingleZonePlateRequest request) {
        RenderJob job = jobs.submit("single", progress -> {
            progress.reportProgress(0.05, "rendering");
            RenderResult result = ZonePlateRenderer.render(request.toParameters());
            progress.reportProgress(1.0, "done");
            return result;
        });
        return Map.of("jobId", job.id());
    }

    @PostMapping(value = "/hex",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> submitHex(@Valid @RequestBody HexMacroCellRequest request) {
        RenderJob job = jobs.submit("hex", progress -> {
            progress.reportProgress(0.05, "rendering hex macro cell");
            RenderResult result = HexMacroCellRenderer.render(request.toParameters());
            progress.reportProgress(1.0, "done");
            return result;
        });
        return Map.of("jobId", job.id());
    }

    @PostMapping(value = "/foil",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> submitFoil(@Valid @RequestBody WindowFoilRequest request) {
        var parameters = request.toParameters();
        RenderJob job = jobs.submit("foil", progress -> {
            progress.reportProgress(0.05, "rendering window foil");
            RenderResult result = WindowFoilRenderer.render(parameters);
            progress.reportProgress(1.0, "done");
            return result;
        });
        return Map.of("jobId", job.id());
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status(
            @PathVariable("id") String id,
            Authentication authentication) {
        return toStatus(requireAuthorized(id, authentication));
    }

    @GetMapping(value = "/{id}/events")
    public SseEmitter events(
            @PathVariable("id") String id,
            Authentication authentication) {
        RenderJob job = requireAuthorized(id, authentication);
        SseEmitter emitter = new SseEmitter(0L);
        Consumer<RenderJob> listener = current -> {
            try {
                emitter.send(SseEmitter.event().name("progress").data(toStatus(current)));
                if (current.isTerminal()) emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        };
        job.addListener(listener);
        emitter.onCompletion(() -> job.removeListener(listener));
        emitter.onTimeout(() -> job.removeListener(listener));
        try {
            emitter.send(SseEmitter.event().name("progress").data(toStatus(job)));
            if (job.isTerminal()) emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @GetMapping(value = "/{id}/result.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> result(
            @PathVariable("id") String id,
            Authentication authentication) throws IOException {
        Access access = access(authentication);
        RenderJob job = jobs.findAuthorized(id, access.requesterId(), access.administrator())
                .orElseThrow(RenderJobController::notFound);
        if (job.state() != RenderJob.State.COMPLETED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("job result is not available".getBytes(StandardCharsets.UTF_8));
        }

        byte[] png;
        RenderResult liveResult = job.result();
        if (liveResult != null) {
            double dpi = 25.4 / liveResult.pixelSizeMm();
            png = PngExporter.toPngBytes(liveResult, dpi);
        } else {
            png = jobs.resultPngAuthorized(id, access.requesterId(), access.administrator())
                    .orElseThrow(RenderJobController::notFound);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("fresnel-job-" + id + ".png")
                .build());
        return new ResponseEntity<>(png, headers, HttpStatus.OK);
    }

    private RenderJob requireAuthorized(String id, Authentication authentication) {
        Access access = access(authentication);
        return jobs.findAuthorized(id, access.requesterId(), access.administrator())
                .orElseThrow(RenderJobController::notFound);
    }

    private static Map<String, Object> toStatus(RenderJob job) {
        boolean failed = job.state() == RenderJob.State.FAILED;
        return Map.of(
                "jobId", job.id(),
                "label", job.label(),
                "state", job.state().name(),
                "progress", job.progress(),
                "message", failed ? "render failed" : job.message(),
                "error", failed ? "render failed" : "");
    }

    private static Access access(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw notFound();
        }
        boolean administrator = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return new Access(authentication.getName(), administrator);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "render job not found");
    }

    private record Access(String requesterId, boolean administrator) {}
}
