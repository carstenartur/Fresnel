package org.fresnel.backend.measurement.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/measurement-sessions")
class MeasurementSessionController {
    private final MeasurementSessions sessions;
    private final MeasurementBundles bundles;
    private final ObjectMapper json;
    MeasurementSessionController(MeasurementSessions sessions, MeasurementBundles bundles, ObjectMapper json) {
        this.sessions = sessions; this.bundles = bundles; this.json = json;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    MeasurementSessions.View create(Principal user, HttpServletRequest request) throws IOException {
        return sessions.create(user.getName(), plan(request));
    }

    @PutMapping(value="/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    MeasurementSessions.View createAt(Principal user, @PathVariable("id") UUID id, HttpServletRequest request) throws IOException {
        return sessions.create(user.getName(), id, plan(request), "manual-upload");
    }

    @PutMapping(value="/{id}/derive/{sourceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    MeasurementSessions.View derive(Principal user, @PathVariable("id") UUID id, @PathVariable("sourceId") UUID sourceId,
                                    HttpServletRequest request) throws IOException {
        var bytes = new ByteArrayOutputStream();
        MeasurementSessions.copy(request.getInputStream(), bytes, MeasurementSessions.PLAN_BYTES + 4096);
        try {
            var input = json.readValue(bytes.toByteArray(), MeasurementSessions.Derivation.class);
            return sessions.derive(user.getName(), id, sourceId, input);
        } catch (tools.jackson.core.JacksonException e) { throw MeasurementSessions.bad("Invalid source mapping"); }
    }

    private MeasurementSessions.Plan plan(HttpServletRequest request) throws IOException {
        var bytes = new ByteArrayOutputStream();
        MeasurementSessions.copy(request.getInputStream(), bytes, MeasurementSessions.PLAN_BYTES);
        try { return json.readValue(bytes.toByteArray(), MeasurementSessions.Plan.class); }
        catch (tools.jackson.core.JacksonException e) { throw MeasurementSessions.bad("Invalid capture plan"); }
    }
    @GetMapping List<MeasurementSessions.View> list(Principal user) throws IOException { return sessions.list(user.getName()); }
    @GetMapping("/{id}") MeasurementSessions.View get(Principal user, @PathVariable("id") UUID id) throws IOException {
        return sessions.get(user.getName(), id);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Principal user, @PathVariable("id") UUID id) throws IOException { sessions.delete(user.getName(), id); }

    @PutMapping("/{id}/assets/{step}")
    MeasurementSessions.View put(Principal user, @PathVariable("id") UUID id, @PathVariable("step") String step,
                                 @RequestHeader("X-Content-SHA256") String sha, HttpServletRequest request) throws IOException {
        return sessions.put(user.getName(), id, step, Objects.toString(request.getContentType(), ""), sha, request.getInputStream());
    }

    @GetMapping("/{id}/assets/{step}")
    ResponseEntity<FileSystemResource> source(Principal user, @PathVariable("id") UUID id, @PathVariable("step") String step) throws IOException {
        var s = sessions.source(user.getName(), id, step);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate()).eTag("\"" + s.asset().sha256() + "\"")
                .contentType(MediaType.parseMediaType(s.asset().mediaType())).body(new FileSystemResource(s.path()));
    }

    @GetMapping("/{id}/bundle")
    void export(Principal user, @PathVariable("id") UUID id, HttpServletResponse response) throws IOException {
        sessions.get(user.getName(), id);
        response.setContentType(MeasurementBundles.TYPE);
        response.setHeader("Content-Disposition", "attachment; filename=\"experiment-" + id + ".fresnel-exp\"");
        bundles.export(user.getName(), id, response.getOutputStream());
    }

    @PostMapping(value = "/import", consumes = MeasurementBundles.TYPE)
    MeasurementSessions.View read(Principal user, HttpServletRequest request) throws IOException {
        return bundles.read(user.getName(), request.getInputStream());
    }
}
