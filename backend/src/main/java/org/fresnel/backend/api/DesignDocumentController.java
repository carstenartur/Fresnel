package org.fresnel.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Save / load endpoints for {@link DesignDocument} envelopes.
 *
 * <p>This is a stateless, file-based persistence facility — no database is
 * required. Clients post a {@link DesignDocument} to {@code /save} and receive
 * the same JSON back as a downloadable attachment. To load, they post the file
 * contents to {@code /load} and receive a validated, normalised
 * {@link DesignDocument} ready to drive any of the other design endpoints.
 */
@RestController
@RequestMapping("/api/designs")
public class DesignDocumentController {

    private final ObjectMapper mapper;

    public DesignDocumentController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

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
