package org.fresnel.backend.desktop;

import org.fresnel.backend.api.FresnelJobDocument;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Sends a file-open request from a secondary launcher to the primary loopback server. */
public final class DesktopOpenClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;

    public DesktopOpenClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    DesktopOpenClient(HttpClient client) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        this.client = client;
    }

    /** Returns true only when the endpoint presents the expected authenticated protocol marker. */
    public boolean ping(DesktopInstanceMetadata metadata) {
        if (metadata == null || !isRecordedProcessAlive(metadata)) return false;
        HttpRequest request = HttpRequest.newBuilder(endpoint(metadata, "/api/internal/desktop/ping"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", bearer(metadata))
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200
                    && DesktopOpenController.PING_BODY.equals(response.body().trim());
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Sends job bytes to the primary instance and returns its one-time browser import ID.
     * No source path is included in the request.
     */
    public String submit(DesktopInstanceMetadata metadata, byte[] jobBytes)
            throws IOException, InterruptedException {
        if (metadata == null) throw new IllegalArgumentException("metadata must not be null");
        if (jobBytes == null || jobBytes.length == 0) {
            throw new IllegalArgumentException("Fresnel job must not be empty");
        }
        if (jobBytes.length > FresnelJobDocument.MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "Fresnel job exceeds the maximum size of "
                            + FresnelJobDocument.MAX_FILE_BYTES + " bytes");
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint(metadata, "/api/internal/desktop/open"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", bearer(metadata))
                .header("Content-Type", FresnelJobDocument.MEDIA_TYPE)
                .header("Accept", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jobBytes))
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 202) {
            throw new IOException("Primary Fresnel instance rejected desktop open request (HTTP "
                    + response.statusCode() + "): " + concise(response.body()));
        }

        String importId = response.body().trim();
        if (!DesktopOpenQueue.isTokenShapeValid(importId)) {
            throw new IOException("Primary Fresnel instance returned an invalid desktop open token");
        }
        return importId;
    }

    public URI browserUri(DesktopInstanceMetadata metadata, String importId) {
        if (!DesktopOpenQueue.isTokenShapeValid(importId)) {
            throw new IllegalArgumentException("Invalid desktop open token");
        }
        return endpoint(metadata, "/?fresnelOpen=" + importId);
    }

    public URI baseBrowserUri(DesktopInstanceMetadata metadata) {
        return endpoint(metadata, "/");
    }

    private static boolean isRecordedProcessAlive(DesktopInstanceMetadata metadata) {
        return ProcessHandle.of(metadata.processId()).map(ProcessHandle::isAlive).orElse(false);
    }

    private static String bearer(DesktopInstanceMetadata metadata) {
        return "Bearer " + metadata.sessionSecret();
    }

    private static URI endpoint(DesktopInstanceMetadata metadata, String pathAndQuery) {
        return URI.create("http://127.0.0.1:" + metadata.port() + pathAndQuery);
    }

    private static String concise(String body) {
        if (body == null || body.isBlank()) return "no response body";
        String normalized = body.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }
}
