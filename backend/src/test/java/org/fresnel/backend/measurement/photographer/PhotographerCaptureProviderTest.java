package org.fresnel.backend.measurement.photographer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.fresnel.measurement.CaptureProvider;
import org.fresnel.measurement.CaptureProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.fresnel.backend.measurement.photographer.PhotographerHttp.JSON;
import static org.junit.jupiter.api.Assertions.*;

class PhotographerCaptureProviderTest {
    @TempDir Path directory;
    private JsonNode cases;
    private HttpServer server;
    private PhotographerPairing.Connection connection;
    private PhotographerCaptureProvider provider;
    private final ArrayDeque<Exchange> replies = new ArrayDeque<>();
    private Throwable serverFailure;
    private String session;

    private interface Exchange { void reply(HttpExchange call) throws Exception; }

    @BeforeEach
    void start() throws IOException {
        var bytes = Files.readAllBytes(Path.of("../docs/integrations/photographer-capture-v1.fixtures.json"));
        cases = JSON.readTree(bytes);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PhotographerHttp.BASE, call -> {
            try (call) {
                assertEquals("Bearer test-only-fixture", call.getRequestHeaders().getFirst("Authorization"));
                replies.removeFirst().reply(call);
            } catch (Throwable ex) {
                if (serverFailure == null) serverFailure = ex;
            }
        });
        server.start();
        connection = new PhotographerPairing.Connection(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "instance-fixture", "paired-fixture", "test-only-fixture", List.of(),
                PhotographerCaptureProvider.descriptor(value("descriptorWithExtension")));
        provider = client();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        if (serverFailure != null) throw new AssertionError("Fixture HTTP exchange failed", serverFailure);
        assertTrue(replies.isEmpty(), "Expected every HTTP exchange");
    }

    @Test
    void negotiatesDiscoveryAndPollsAsyncStepUsingCanonicalFixtures() {
        reply("/health", 200, value("health"));
        assertEquals(CaptureProvider.HealthState.CONNECTED, provider.health().state());
        reply("/cameras", 200, value("cameras"));
        assertEquals("profile-mock-1", provider.listDevices().getFirst().id());
        assertTrue(provider.descriptor().supports(CaptureProvider.Capability.EXTERNAL_STEP_TRIGGER));
        create();
        replies.add(call -> {
            assertPath(call, "/capture-sessions/capture-bos/steps/reference/trigger");
            assertEquals(value("bosTrigger"), JSON.readTree(call.getRequestBody()));
            assertEquals("trigger-key", call.getRequestHeaders().getFirst("Idempotency-Key"));
            json(call, 202, value("capturePending"));
        });
        reply("/capture-sessions/capture-bos", 200, value("captureCompleted"));
        var t = new CaptureProvider.StepTrigger(null, null, Instant.parse("2026-01-01T00:00:00Z"));
        var result = provider.triggerStep(session, "reference", t, "trigger-key");
        assertEquals("asset-reference", result.asset().assetId());
        reply("/capture-sessions/capture-bos/steps/reference/trigger", 200, value("referenceResult"));
        assertEquals(result, client().triggerStep(session, "reference", t, "trigger-key"));
        var different = new CaptureProvider.StepTrigger(null, null, t.presentedAt().plusSeconds(1));
        assertEquals(CaptureProvider.FailureCode.CONFLICT, assertThrows(CaptureProviderException.class,
                () -> client().triggerStep(session, "reference", different, "trigger-key")).code());
        reply("/capture-sessions/capture-bos/events?afterEventId=1", 200, value("completionEventPage"));
        assertEquals(2, provider.events(session, 1).events().size());
        reply("/capture-sessions/capture-bos", 200, value("captureFailed"));
        assertEquals(CaptureProvider.FailureCode.DEVICE_UNAVAILABLE, provider.getSession(session).failure().code());
        reply("/capture-sessions/capture-bos/cancel", 204, null);
        provider.cancel(session, "cancel-key");
        reply("/health", 401, value("AUTHENTICATION_FAILED"));
        assertEquals(CaptureProvider.HealthState.AUTHENTICATION_FAILED, provider.health().state());
    }

    @Test
    void assemblesInterruptedMediaAndChecksOriginalDigest() throws Exception {
        create();
        var item = cases.get("media").get(0);
        byte[] source = HexFormat.of().parseHex(item.get("bytesHex").asText());
        String etag = item.get("etag").asText();
        metadata(item.get("metadata"));
        replies.add(call -> {
            mediaHeaders(call, etag);
            call.sendResponseHeaders(200, source.length);
            call.getResponseBody().write(source, 0, 15);
            call.getResponseBody().flush();
            // Closing the response ends this transfer before the declared full length.
        });
        replies.add(call -> {
            assertEquals("bytes=15-68", call.getRequestHeaders().getFirst("Range"));
            assertEquals(etag, call.getRequestHeaders().getFirst("If-Range"));
            mediaHeaders(call, etag);
            call.getResponseHeaders().set("Content-Range", "bytes 15-68/69");
            call.sendResponseHeaders(206, 54);
            call.getResponseBody().write(source, 15, 54);
        });
        try (var in = provider.openAsset(session, "asset-reference", CaptureProvider.FullAsset.INSTANCE)) {
            assertArrayEquals(source, in.readAllBytes());
        }
        metadata(item.get("metadata"));
        replies.add(call -> {
            // A full 200 after If-Range cannot be appended to an earlier prefix.
            assertEquals(etag, call.getRequestHeaders().getFirst("If-Range"));
            mediaHeaders(call, etag);
            call.sendResponseHeaders(200, source.length);
            call.getResponseBody().write(source);
        });
        try (var in = client().openAsset(session, "asset-reference", new CaptureProvider.ByteRange(5, 9))) {
            assertArrayEquals(Arrays.copyOfRange(source, 5, 14), in.readAllBytes());
        }
        metadata(item.get("metadata"));
        replies.add(call -> {
            var corrupt = HexFormat.of().parseHex(cases.get("clientChecks").get(1).get("assembledBytesHex").asText());
            mediaHeaders(call, etag);
            call.sendResponseHeaders(200, corrupt.length);
            call.getResponseBody().write(corrupt);
        });
        assertEquals(CaptureProvider.FailureCode.INTEGRITY_MISMATCH, assertThrows(CaptureProviderException.class,
                () -> client().openAsset(session, "asset-reference", CaptureProvider.FullAsset.INSTANCE)).code());
        metadata(cases.get("media").get(1).get("metadata"));
        assertEquals(CaptureProvider.FailureCode.INTEGRITY_MISMATCH, assertThrows(CaptureProviderException.class,
                () -> client().getAssetMetadata(session, "asset-reference")).code());
    }

    @Test
    void rejectsDifferentResultRoleAndUnknownRecoveryState() {
        create();
        var t = new CaptureProvider.StepTrigger(null, null, Instant.now());
        reply("/capture-sessions/capture-bos/steps/reference/trigger", 200, value("disturbedResult"));
        assertEquals(CaptureProvider.FailureCode.INTEGRITY_MISMATCH, assertThrows(CaptureProviderException.class,
                () -> provider.triggerStep(session, "reference", t, "original-key")).code());
        reply("/capture-sessions/capture-bos/steps/disturbed/trigger", 202, value("captureRestartedBetweenSteps"));
        assertEquals(CaptureProvider.FailureCode.CONFLICT, assertThrows(CaptureProviderException.class,
                () -> provider.triggerStep(session, "disturbed", t, "second-key")).code());
    }

    private void create() {
        replies.add(call -> {
            assertPath(call, "/capture-sessions");
            assertEquals(value("bosPlan"), JSON.readTree(call.getRequestBody()));
            json(call, 201, value("sessionRef"));
        });
        session = provider.createSession(PhotographerInteropTest.bos("profile-mock-1"), "fixture-bos-1", "create-key").id();
    }

    private PhotographerCaptureProvider client() {
        return new PhotographerCaptureProvider(() -> Optional.of(connection), directory);
    }

    private JsonNode value(String name) { return cases.get("values").required(name); }

    private void metadata(JsonNode w) { reply("/capture-sessions/capture-bos/assets/asset-reference/metadata", 200, w); }

    private void mediaHeaders(HttpExchange call, String etag) {
        assertPath(call, "/capture-sessions/capture-bos/assets/asset-reference");
        call.getResponseHeaders().set("Content-Type", "image/png");
        call.getResponseHeaders().set("ETag", etag);
    }

    private void reply(String path, int status, JsonNode body) {
        replies.add(call -> { assertPath(call, path); json(call, status, body); });
    }

    private static void assertPath(HttpExchange call, String path) {
        assertEquals(PhotographerHttp.BASE + path, call.getRequestURI().toString());
    }

    private static void json(HttpExchange call, int status, JsonNode body) throws IOException {
        if (body == null) {
            call.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = JSON.writeValueAsBytes(body);
        call.getResponseHeaders().set("Content-Type", "application/json");
        call.sendResponseHeaders(status, bytes.length);
        call.getResponseBody().write(bytes);
    }
}
