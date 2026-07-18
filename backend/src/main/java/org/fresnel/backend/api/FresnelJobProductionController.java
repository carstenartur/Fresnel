package org.fresnel.backend.api;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Executes one declared output from a canonical `.fresnel` production job. */
@RestController
@RequestMapping("/api/designs/job")
public class FresnelJobProductionController {

    private final FresnelJobService jobService;
    private final FresnelJobExecutor executor;

    public FresnelJobProductionController(
            FresnelJobService jobService,
            FresnelJobExecutor executor) {
        this.jobService = jobService;
        this.executor = executor;
    }

    /**
     * Executes exactly the production output identified by {@code outputId}. The
     * job remains the source of plugin, parameters, format, filename and options;
     * the URL selects only which already-declared output is returned.
     */
    @PostMapping(
            value = "/execute/{outputId}",
            consumes = {FresnelJobDocument.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<byte[]> execute(
            @PathVariable("outputId") String outputId,
            @RequestBody byte[] body) throws IOException {
        FresnelJobDocument job = jobService.parseAndNormalize(body);
        if (job.production() == null || job.production().outputs() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Fresnel job has no production outputs");
        }

        FresnelJobDocument.ProductionOutput selectedOutput = job.production().outputs().stream()
                .filter(output -> output.id().equals(outputId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Fresnel job contains no production output with id " + outputId));
        FresnelJobDocument selectedJob = new FresnelJobDocument(
                job.schema(),
                job.format(),
                job.formatVersion(),
                job.plugin(),
                job.parameters(),
                new FresnelJobDocument.ProductionPlan(List.of(selectedOutput)),
                job.provenance());

        AtomicReference<GeneratedArtifact> generatedArtifact = new AtomicReference<>();
        AtomicReference<byte[]> generatedContent = new AtomicReference<>();
        executor.execute(selectedJob, (artifact, content) -> {
            generatedArtifact.set(artifact);
            generatedContent.set(content.clone());
        });

        GeneratedArtifact artifact = generatedArtifact.get();
        byte[] content = generatedContent.get();
        if (artifact == null || content == null) {
            throw new IllegalStateException("Fresnel job executor produced no artifact");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(artifact.mediaType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(artifact.filename())
                .build());
        headers.set("X-Fresnel-Normalized-SHA256", artifact.normalizedSha256());
        return ResponseEntity.ok().headers(headers).body(content);
    }
}
