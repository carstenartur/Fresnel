package org.fresnel.backend.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "fresnel.desktop.enabled=true",
                "fresnel.desktop.instance-secret=0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
        })
class DesktopOpenClientIntegrationTest {

    private static final String SECRET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";

    @LocalServerPort int port;
    @Autowired DesktopOpenQueue queue;

    @Test
    void secondaryClientAuthenticatesAndSubmitsOnlyJobBytes() throws Exception {
        DesktopInstanceMetadata metadata = metadata(SECRET);
        DesktopOpenClient client = new DesktopOpenClient();

        assertTrue(client.ping(metadata));
        String token = client.submit(metadata, validJob());
        DesktopOpenResult result = queue.consume(token).orElseThrow();
        assertTrue(result.valid());
        assertEquals("zone-plate", result.job().plugin().id());
    }

    @Test
    void wrongSecretFailsTheHandshakeAndSubmission() {
        DesktopOpenClient client = new DesktopOpenClient();
        DesktopInstanceMetadata wrong = metadata("X".repeat(43));

        assertFalse(client.ping(wrong));
        IOException failure = assertThrows(IOException.class,
                () -> client.submit(wrong, validJob()));
        assertTrue(failure.getMessage().contains("HTTP 401"));
    }

    private DesktopInstanceMetadata metadata(String secret) {
        return new DesktopInstanceMetadata(
                DesktopInstanceMetadata.CURRENT_PROTOCOL_VERSION,
                port,
                ProcessHandle.current().pid(),
                secret,
                System.currentTimeMillis());
    }

    private static byte[] validJob() {
        return """
                {
                  "format": "io.github.carstenartur.fresnel.job",
                  "formatVersion": 1,
                  "plugin": {
                    "id": "zone-plate",
                    "parameterSchemaVersion": 1,
                    "algorithmVersion": "zone-plate/1"
                  },
                  "parameters": {
                    "apertureDiameterMm": 10,
                    "focalLengthMm": 1000,
                    "wavelengthNm": 550,
                    "dpi": 1200
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
    }
}
