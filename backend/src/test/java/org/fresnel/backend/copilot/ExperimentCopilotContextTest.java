package org.fresnel.backend.copilot;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperimentCopilotContextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyKnownCorrectlyTypedCurrentParameters() {
        ObjectNode current = mapper.createObjectNode()
                .put("wavelengthNm", 532.0)
                .put("maskType", "BINARY_AMPLITUDE");

        ExperimentCopilotContext context = new ExperimentCopilotContext(
                "Create a one metre Zone Plate.", schema(), defaults(), current);

        assertEquals(532.0, context.currentParameters().path("wavelengthNm").doubleValue());
        current.put("wavelengthNm", 633.0);
        assertEquals(532.0, context.currentParameters().path("wavelengthNm").doubleValue(),
                "provider context must retain a defensive copy");
    }

    @Test
    void rejectsUnknownCurrentFieldBeforeAProviderCanReceiveIt() {
        ObjectNode current = mapper.createObjectNode().put("shellCommand", "do not send");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ExperimentCopilotContext(
                        "Create a 532 nm Zone Plate.", schema(), defaults(), current));

        assertEquals("currentParameters contains unknown field: shellCommand", error.getMessage());
    }

    @Test
    void rejectsWrongTypeUnknownEnumAndNullValues() {
        ObjectNode wrongType = mapper.createObjectNode().put("wavelengthNm", "532");
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentCopilotContext("request", schema(), defaults(), wrongType));

        ObjectNode wrongEnum = mapper.createObjectNode().put("maskType", "EXECUTABLE");
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentCopilotContext("request", schema(), defaults(), wrongEnum));

        ObjectNode nullValue = mapper.createObjectNode().putNull("maskType");
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentCopilotContext("request", schema(), defaults(), nullValue));
    }

    @Test
    void rejectsOversizedRequestAndCurrentContext() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentCopilotContext(
                        "x".repeat(ExperimentCopilotContext.MAX_REQUEST_CHARS + 1),
                        schema(), defaults(), null));

        ObjectNode oversized = mapper.createObjectNode().put(
                "maskType",
                "x".repeat(ExperimentCopilotContext.MAX_CURRENT_PARAMETERS_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> new ExperimentCopilotContext("request", schema(), defaults(), oversized));
    }

    private ObjectNode schema() {
        ObjectNode schema = mapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("wavelengthNm").put("type", "number");
        ObjectNode mask = properties.putObject("maskType").put("type", "string");
        mask.putArray("enum").add("BINARY_AMPLITUDE").add("GREYSCALE_PHASE");
        return schema;
    }

    private ObjectNode defaults() {
        return mapper.createObjectNode()
                .put("wavelengthNm", 550.0)
                .put("maskType", "BINARY_AMPLITUDE");
    }
}
