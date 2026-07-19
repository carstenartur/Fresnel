package org.fresnel.backend.copilot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiExperimentCopilotProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void usesBearerSecretAndParsesStrictStructuredOutput() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = server(200, exchangeBody("""
                {
                  "selectedPluginId":"zone-plate",
                  "parameters":[
                    {"path":"wavelengthNm","value":532,"source":"USER_SUPPLIED","rationale":"stated"},
                    {"path":"focalLengthMm","value":1000,"source":"USER_SUPPLIED","rationale":"stated"}
                  ],
                  "unresolvedQuestions":[],
                  "alternatives":[],
                  "summary":"Grounded Zone Plate proposal"
                }
                """), authorization);

        OpenAiExperimentCopilotProvider provider = OpenAiExperimentCopilotProvider.forTesting(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                "secret-test-key",
                endpoint(),
                "gpt-5.6");

        ExperimentProposal proposal = provider.propose(new ExperimentCopilotContext(
                "Create a 532 nm zone plate with a 1 m focus.",
                new ObjectMapper().createObjectNode(),
                new ObjectMapper().createObjectNode(),
                null));

        assertEquals("Bearer secret-test-key", authorization.get());
        assertEquals("zone-plate", proposal.selectedPluginId());
        assertEquals(2, proposal.parameters().size());
        assertTrue(proposal.unresolvedQuestions().isEmpty());
    }

    @Test
    void quotaFailureIsClassifiedWithoutLeakingTheKey() throws Exception {
        server = server(429, "{\"error\":{\"code\":\"insufficient_quota\"}}", new AtomicReference<>());
        String key = "secret-that-must-not-appear";
        OpenAiExperimentCopilotProvider provider = OpenAiExperimentCopilotProvider.forTesting(
                new ObjectMapper(), HttpClient.newHttpClient(), key, endpoint(), "gpt-5.6");

        CopilotProviderException error = assertThrows(
                CopilotProviderException.class,
                () -> provider.propose(new ExperimentCopilotContext(
                        "Create a 532 nm zone plate with a 1 m focus.",
                        new ObjectMapper().createObjectNode(),
                        new ObjectMapper().createObjectNode(),
                        null)));

        assertEquals("QUOTA_OR_RATE_LIMIT", error.code());
        assertFalse(error.getMessage().contains(key));
    }

    private HttpServer server(
            int status,
            String body,
            AtomicReference<String> authorization) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        created.createContext("/v1/responses", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        created.start();
        return created;
    }

    private java.net.URI endpoint() {
        return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
    }

    private static String exchangeBody(String structuredJson) {
        String escaped = structuredJson
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
        return "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\""
                + escaped + "\"}]}]}";
    }
}
