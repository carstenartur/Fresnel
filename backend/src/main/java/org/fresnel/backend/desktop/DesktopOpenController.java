package org.fresnel.backend.desktop;

import jakarta.servlet.http.HttpServletRequest;
import org.fresnel.backend.api.FresnelJobDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Loopback-only hand-off surface used by packaged desktop launcher invocations.
 *
 * <p>The internal endpoints additionally require a random per-process bearer secret.
 * The browser receives only an opaque, short-lived, one-time import token.</p>
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "fresnel.desktop.enabled", havingValue = "true")
public class DesktopOpenController {

    static final String PING_BODY = "fresnel-desktop-v1";

    private final DesktopOpenQueue queue;
    private final byte[] expectedSecret;

    public DesktopOpenController(
            DesktopOpenQueue queue,
            @Value("${fresnel.desktop.instance-secret:}") String instanceSecret) {
        if (instanceSecret == null || instanceSecret.length() < 32) {
            throw new IllegalArgumentException(
                    "fresnel.desktop.instance-secret must contain at least 32 characters");
        }
        this.queue = queue;
        this.expectedSecret = instanceSecret.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/internal/desktop/ping", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ping(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireLoopback(request);
        requireSecret(authorization);
        return noStore(ResponseEntity.ok(PING_BODY));
    }

    @PostMapping(
            value = "/internal/desktop/open",
            consumes = {FresnelJobDocument.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> open(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody byte[] body) {
        requireLoopback(request);
        requireSecret(authorization);
        if (body == null || body.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fresnel job must not be empty");
        }
        if (body.length > FresnelJobDocument.MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Fresnel job exceeds the maximum size of "
                            + FresnelJobDocument.MAX_FILE_BYTES + " bytes");
        }
        String importId = queue.enqueue(body);
        return noStore(ResponseEntity.status(HttpStatus.ACCEPTED).body(importId));
    }

    @GetMapping(value = "/desktop/open/{importId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DesktopOpenResult> consume(
            HttpServletRequest request,
            @PathVariable("importId") String importId) {
        requireLoopback(request);
        DesktopOpenResult result = queue.consume(importId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Desktop open token is invalid, expired or already consumed"));
        return noStore(ResponseEntity.ok(result));
    }

    private void requireSecret(String authorization) {
        byte[] supplied = bearerToken(authorization).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedSecret, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid desktop instance secret");
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return "";
        return authorization.substring("Bearer ".length());
    }

    private static void requireLoopback(HttpServletRequest request) {
        try {
            String remoteAddress = request.getRemoteAddr();
            if (remoteAddress == null || !InetAddress.getByName(remoteAddress).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Desktop open endpoints are available only from loopback");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Could not verify loopback client", e);
        }
    }

    private static <T> ResponseEntity<T> noStore(ResponseEntity<T> response) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.setCacheControl(CacheControl.noStore().mustRevalidate().sMaxAge(Duration.ZERO));
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }
}
