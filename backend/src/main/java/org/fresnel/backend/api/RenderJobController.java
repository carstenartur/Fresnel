package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.backend.jobs.RenderJob;
import org.fresnel.backend.jobs.RenderJobService;
import org.fresnel.optics.HexMacroCellRenderer;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.fresnel.optics.WindowFoilRenderer;
import org.fresnel.optics.ZonePlateRenderer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * Async render-job endpoints. Submit a job → poll status / subscribe SSE → fetch
 * the resulting PNG by job id.
 *
 * <p>Designed for renders that exceed the synchronous {@link DesignController#MAX_PREVIEW_PX}
 * cap (e.g. window-foil sheets at production DPI, large hex macro cells).
 */
@RestController
@RequestMapping("/api/jobs")
public class RenderJobController {

    private final RenderJobService jobs;

    public RenderJobController(RenderJobService jobs) {
        this.jobs = jobs;
    }

    // -------- Submit --------

    @PostMapping(value = "/single",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> submitSingle(@Valid @RequestBody SingleZonePlateRequest req) {
        RenderJob job = jobs.submit("single", j -> {
            j.reportProgress(0.05, "rendering");
            RenderResult r = ZonePlateRenderer.render(req.toParameters());
            j.reportProgress(1.0, "done");
            return r;
        });
        return Map.of("jobId", job.id());
    }

    @PostMapping(value = "/hex",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> submitHex(@Valid @RequestBody HexMacroCellRequest req) {
        RenderJob job = jobs.submit("hex", j -> {
            j.reportProgress(0.05, "rendering hex macro cell");
            RenderResult r = HexMacroCellRenderer.render(req.toParameters());
            j.reportProgress(1.0, "done");
            return r;
        });
        return Map.of("jobId", job.id());
    }

    @PostMapping(value = "/foil",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> submitFoil(@Valid @RequestBody WindowFoilRequest req) {
        var params = req.toParameters();
        RenderJob job = jobs.submit("foil", j -> {
            j.reportProgress(0.05, "rendering window foil");
            RenderResult r = WindowFoilRenderer.render(params);
            j.reportProgress(1.0, "done");
            return r;
        });
        return Map.of("jobId", job.id());
    }

    // -------- Poll --------

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> status(@PathVariable("id") String id) {
        RenderJob job = jobs.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toStatus(job));
    }

    private static Map<String, Object> toStatus(RenderJob j) {
        return Map.of(
                "jobId", j.id(),
                "label", j.label(),
                "state", j.state().name(),
                "progress", j.progress(),
                "message", j.message(),
                "error", j.error() == null ? "" : String.valueOf(j.error().getMessage()));
    }

    // -------- SSE progress stream --------

    @GetMapping(value = "/{id}/events")
    public SseEmitter events(@PathVariable("id") String id) {
        RenderJob job = jobs.get(id);
        if (job == null) {
            // Use a proper 404 instead of an SSE stream that completes with an error,
            // so clients can distinguish a missing job from a transient stream error.
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "unknown job id: " + id);
        }
        SseEmitter emitter = new SseEmitter(0L);
        java.util.function.Consumer<RenderJob> listener = j -> {
            try {
                emitter.send(SseEmitter.event().name("progress").data(toStatus(j)));
                if (j.isTerminal()) emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        };
        job.addListener(listener);
        emitter.onCompletion(() -> job.removeListener(listener));
        emitter.onTimeout(() -> job.removeListener(listener));
        // Send the current state immediately so late subscribers get the latest snapshot.
        try {
            emitter.send(SseEmitter.event().name("progress").data(toStatus(job)));
            if (job.isTerminal()) emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // -------- Result --------

    @GetMapping(value = "/{id}/result.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> result(@PathVariable("id") String id) throws IOException {
        RenderJob job = jobs.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.state() != RenderJob.State.COMPLETED) {
            return ResponseEntity.status(409)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("job not yet complete (state=" + job.state() + ")").getBytes());
        }
        RenderResult r = job.result();
        // Find a sensible DPI: derive from pixel size (mm/pixel → dpi = 25.4 / mmPerPixel).
        double dpi = 25.4 / r.pixelSizeMm();
        byte[] png = PngExporter.toPngBytes(r, dpi);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.IMAGE_PNG);
        h.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
                .filename("fresnel-job-" + id + ".png").build());
        return new ResponseEntity<>(png, h, 200);
    }
}
