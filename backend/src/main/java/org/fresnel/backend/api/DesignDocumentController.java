package org.fresnel.backend.api;

import org.fresnel.backend.persistence.DesignEntity;
import org.fresnel.backend.persistence.DesignRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Save / load endpoints for legacy {@link DesignDocument} envelopes and canonical
 * {@link FresnelJobDocument} files.
 *
 * <p>This controller offers three complementary persistence modes:</p>
 *
 * <ul>
 *   <li><b>Canonical job round-trip</b>: {@code POST /job/save} and
 *       {@code POST /job/load} validate, migrate and normalize a versioned
 *       {@code .fresnel} job. The load endpoint also accepts legacy design JSON.
 *   <li><b>Legacy stateless round-trip</b>: {@code POST /save} returns the
 *       canonicalised legacy JSON as a downloadable attachment; {@code POST /load}
 *       validates and echoes a posted document. Kept for compatibility.
 *   <li><b>Server-side persistence</b>: {@code POST /persist} stores a legacy design
 *       in the database (Postgres in production, H2 in local/dev/tests) and returns
 *       the assigned UUID; {@code GET /persist} lists designs scoped to the caller
 *       (admin sees all); {@code GET /persist/{id}} loads a specific design.
 * </ul>
 */
@RestController
@RequestMapping("/api/designs")
public class DesignDocumentController {

    private final ObjectMapper mapper;
    private final DesignRepository repository;
    private final FresnelJobService jobService;

    public DesignDocumentController(
            ObjectMapper mapper,
            DesignRepository repository,
            FresnelJobService jobService) {
        this.mapper = mapper;
        this.repository = repository;
        this.jobService = jobService;
    }

    // -------- Canonical .fresnel job round-trip --------

    @PostMapping(
            value = "/job/save",
            consumes = {MediaType.APPLICATION_JSON_VALUE, FresnelJobDocument.MEDIA_TYPE},
            produces = FresnelJobDocument.MEDIA_TYPE)
    public ResponseEntity<byte[]> saveJob(@RequestBody byte[] body) {
        FresnelJobDocument job = jobService.parseAndNormalize(body);
        byte[] canonical = jobService.write(job);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(FresnelJobDocument.MEDIA_TYPE));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"fresnel-" + job.plugin().id()
                        + FresnelJobDocument.FILE_EXTENSION + "\"");
        return ResponseEntity.ok().headers(headers).body(canonical);
    }

    @PostMapping(
            value = "/job/load",
            consumes = {MediaType.APPLICATION_JSON_VALUE, FresnelJobDocument.MEDIA_TYPE},
            produces = FresnelJobDocument.MEDIA_TYPE)
    public ResponseEntity<byte[]> loadJob(@RequestBody byte[] body) {
        FresnelJobDocument job = jobService.parseAndNormalize(body);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(FresnelJobDocument.MEDIA_TYPE))
                .body(jobService.write(job));
    }

    // -------- Legacy stateless round-trip --------

    @PostMapping(value = "/save",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> save(@RequestBody DesignDocument doc) throws Exception {
        validate(doc);
        DesignDocument normalised = new DesignDocument(
                doc.kind(),
                doc.version() <= 0 ? DesignDocument.SCHEMA_VERSION : doc.version(),
                doc.payload());
        byte[] body = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(normalised);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"fresnel-design-" + normalised.kind() + ".json\"");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PostMapping(value = "/load",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DesignDocument load(@RequestBody DesignDocument doc) {
        validate(doc);
        if (doc.version() > DesignDocument.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Design schema version " + doc.version() + " is newer than supported ("
                            + DesignDocument.SCHEMA_VERSION + "). Please upgrade.");
        }
        return doc;
    }

    // -------- Server-side persistence --------

    @PostMapping(value = "/persist",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> persist(@RequestBody DesignDocument doc) throws Exception {
        validate(doc);
        String name = (doc.payload().has("name") && doc.payload().get("name").isTextual())
                ? doc.payload().get("name").asText() : null;
        DesignEntity entity = new DesignEntity(
                null,
                doc.kind(),
                doc.version() <= 0 ? DesignDocument.SCHEMA_VERSION : doc.version(),
                name,
                currentOwnerOrNull(),
                mapper.writeValueAsString(doc.payload()));
        DesignEntity saved = repository.save(entity);
        return Map.of("id", saved.getId().toString());
    }

    @GetMapping(value = "/persist", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> list() {
        List<DesignEntity> rows = isAdmin()
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findAllByOwnerIdOrderByCreatedAtDesc(currentOwnerOrNull());
        return rows.stream().map(e -> Map.<String, Object>of(
                "id", e.getId().toString(),
                "kind", e.getKind(),
                "name", e.getName() == null ? "" : e.getName(),
                "version", e.getSchemaVersion(),
                "ownerId", e.getOwnerId() == null ? "" : e.getOwnerId(),
                "createdAt", e.getCreatedAt().toString(),
                "updatedAt", e.getUpdatedAt().toString())).toList();
    }

    @GetMapping(value = "/persist/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DesignDocument loadById(@PathVariable("id") String id) throws Exception {
        UUID uuid = parseUuid(id);
        DesignEntity entity = repository.findById(uuid)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "design not found: " + id));
        if (!isAdmin() && entity.getOwnerId() != null
                && !entity.getOwnerId().equals(currentOwnerOrNull())) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "not the owner");
        }
        JsonNode payload = mapper.readTree(entity.getPayloadJson());
        return new DesignDocument(entity.getKind(), entity.getSchemaVersion(), payload);
    }

    @DeleteMapping("/persist/{id}")
    public ResponseEntity<Void> deleteById(@PathVariable("id") String id) {
        UUID uuid = parseUuid(id);
        DesignEntity entity = repository.findById(uuid)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "design not found: " + id));
        if (!isAdmin() && entity.getOwnerId() != null
                && !entity.getOwnerId().equals(currentOwnerOrNull())) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "not the owner");
        }
        repository.delete(entity);
        return ResponseEntity.noContent().build();
    }

    // -------- Helpers --------

    private static UUID parseUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalid UUID: " + id);
        }
    }

    private static String currentOwnerOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        return (name == null || "anonymousUser".equals(name)) ? null : name;
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static void validate(DesignDocument doc) {
        if (doc == null) {
            throw new IllegalArgumentException("design document must not be null");
        }
        if (doc.kind() == null || doc.kind().isBlank()) {
            throw new IllegalArgumentException("design document 'kind' must not be empty");
        }
        if (!DesignDocument.isKnownKind(doc.kind())) {
            throw new IllegalArgumentException("unknown design kind: " + doc.kind());
        }
        if (doc.payload() == null || doc.payload().isNull()) {
            throw new IllegalArgumentException("design document 'payload' must not be empty");
        }
    }
}
