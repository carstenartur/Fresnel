package org.fresnel.backend.api;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundOrientedSchlierenJobTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FresnelJobService service = new FresnelJobService(
            mapper,
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void normalizesAndHashesTheCompleteTargetIdentity() {
        JsonNode parameters = mapper.valueToTree(
                BackgroundOrientedSchlierenRequest.defaults());
        FresnelJobDocument source = job(parameters);

        FresnelJobDocument normalized = service.normalize(source);

        assertEquals("background-oriented-schlieren", normalized.plugin().id());
        assertEquals(20_260_824L, normalized.parameters().path("patternSeed").longValue());
        assertEquals(1920, normalized.parameters().path("widthPx").intValue());
        assertNotNull(normalized.provenance());
        assertTrue(normalized.provenance().parameterSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsUnknownFieldsAndCrossFieldPixelBombs() {
        JsonNode unknown = mapper.createObjectNode()
                .setAll((tools.jackson.databind.node.ObjectNode) mapper.valueToTree(
                        BackgroundOrientedSchlierenRequest.defaults()));
        ((tools.jackson.databind.node.ObjectNode) unknown).put("command", "rm -rf /");
        assertThrows(IllegalArgumentException.class, () -> service.normalize(job(unknown)));

        tools.jackson.databind.node.ObjectNode tooLarge = mapper.createObjectNode();
        tooLarge.setAll((tools.jackson.databind.node.ObjectNode) mapper.valueToTree(
                BackgroundOrientedSchlierenRequest.defaults()));
        tooLarge.put("widthPx", 4096);
        tooLarge.put("heightPx", 4096);
        assertThrows(IllegalArgumentException.class, () -> service.normalize(job(tooLarge)));
    }

    @Test
    void parameterHashChangesWhenTheSeedChanges() {
        tools.jackson.databind.node.ObjectNode first =
                (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(
                        BackgroundOrientedSchlierenRequest.defaults());
        tools.jackson.databind.node.ObjectNode second = first.deepCopy();
        second.put("patternSeed", 99);

        String firstHash = service.normalize(job(first)).provenance().parameterSha256();
        String secondHash = service.normalize(job(second)).provenance().parameterSha256();
        assertTrue(!firstHash.equals(secondHash));
    }

    private static FresnelJobDocument job(JsonNode parameters) {
        return new FresnelJobDocument(
                FresnelJobDocument.SCHEMA_URL,
                FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION,
                new FresnelJobDocument.PluginRef(
                        "background-oriented-schlieren",
                        FresnelJobDocument.CURRENT_PARAMETER_SCHEMA_VERSION,
                        "background-oriented-schlieren/1"),
                parameters,
                null,
                null);
    }
}
