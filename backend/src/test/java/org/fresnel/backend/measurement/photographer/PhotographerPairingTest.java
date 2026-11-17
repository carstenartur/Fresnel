package org.fresnel.backend.measurement.photographer;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PhotographerPairingTest {
    @TempDir Path data;

    @Test
    void exchangesOnceAndRetainsOriginBoundCredentialsAcrossRestart() throws Exception {
        var json = JsonMapper.builder().build();
        var fixtures = json.readTree(Path.of("..", "docs", "integrations", "photographer-capture-v1.fixtures.json").toFile())
                .required("values");
        var posts = new AtomicInteger();
        var localId = new String[1];
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/api/v1/integrations/fresnel/pair", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertNotNull(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            var request = json.readTree(exchange.getRequestBody());
            assertEquals("test-code", request.required("pairingCode").asString());
            assertEquals(1, request.required("fresnelProtocolVersion").asInt());
            String id = request.required("fresnelInstanceId").asString();
            if (localId[0] == null) localId[0] = id;
            else assertEquals(localId[0], id);
            posts.incrementAndGet();
            byte[] reply = json.writeValueAsBytes(fixtures.required("pairResult"));
            exchange.sendResponseHeaders(201, reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        remote.start();
        try {
            String origin = "http://127.0.0.1:" + remote.getAddress().getPort();
            var service = new PhotographerPairing(origin, false, data);
            assertTrue(service.current().isEmpty());
            service.pair("test-code");
            var restored = new PhotographerPairing(origin, false, data);
            var connection = restored.current().orElseThrow();
            assertEquals("pair-fixture", connection.pairingId());
            assertEquals("photographer-fixture", connection.instanceId());
            assertEquals("test-only-bearer-fixture", connection.bearerToken());
            assertFalse(connection.toString().contains(connection.bearerToken()));
            assertTrue(connection.scopes().contains("fresnel:media:read"));
            assertFalse(json.writeValueAsString(restored.status()).contains(connection.bearerToken()));
            assertThrows(PhotographerPairing.Failure.class, () -> restored.pair("another"));
            assertEquals(1, posts.get());
            var other = new PhotographerPairing("https://another.example", false, data);
            assertTrue(other.current().isEmpty());
            assertTrue(other.status().paired());
            assertFalse(other.status().originMatches());
            assertThrows(PhotographerPairing.Failure.class, () -> other.pair("another"));
            restored.forget();
            assertTrue(service.current().isEmpty());
            service.pair("test-code");
            assertEquals(2, posts.get());
            String manifest = Files.readString(data.resolve("connection.json"));
            assertFalse(manifest.contains("test-code"));
            if (Files.getFileStore(data).supportsFileAttributeView("posix")) {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(data.resolve("connection.json"))));
            }
        } finally { remote.stop(0); }
    }

    @Test
    void rejectsAnUntrustedHttpOriginAndRedirectDuringExchange() throws Exception {
        for (String invalid : new String[]{"http://remote.example", "https://user@example.test",
                "https://example.test/private", "https://example.test?secret=query", "file:///tmp/camera"}) {
            assertThrows(IllegalArgumentException.class, () -> new PhotographerPairing(invalid, false, data));
        }
        new PhotographerPairing("http://photographer:8080", true, data);
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var followed = new AtomicInteger();
        remote.createContext("/api/v1/integrations/fresnel/pair", exchange -> {
            exchange.getResponseHeaders().add("Location", "/other");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        remote.createContext("/other", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        remote.start();
        try {
            var service = new PhotographerPairing("http://127.0.0.1:" + remote.getAddress().getPort(), false, data);
            assertThrows(PhotographerPairing.Failure.class, () -> service.pair("test-code"));
            assertTrue(service.current().isEmpty());
            assertEquals(0, followed.get());
        } finally { remote.stop(0); }
    }
}
