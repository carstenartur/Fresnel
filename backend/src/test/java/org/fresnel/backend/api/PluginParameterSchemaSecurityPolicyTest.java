package org.fresnel.backend.api;

import org.fresnel.optics.PluginDescriptor;
import org.fresnel.optics.PluginRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PluginParameterSchemaSecurityPolicyTest {

    @Autowired PluginSchemaService schemaService;

    @Test
    void acceptsEveryRegisteredPublishedParameterSchema() {
        for (PluginDescriptor descriptor : PluginRegistry.ALL) {
            JsonNode schema = schemaService.requireByPluginId(descriptor.id()).parameterSchema();
            assertDoesNotThrow(
                    () -> PluginParameterSchemaSecurityPolicy.validate(descriptor, schema),
                    descriptor.id());
        }
    }

    @Test
    void rejectsRemoteAndLocalSchemaReferences() {
        ObjectNode remote = zonePlateSchema();
        field(remote, "apertureDiameterMm").put(
                "$ref", "https://example.invalid/arbitrary.schema.json");
        IllegalStateException remoteError = assertThrows(
                IllegalStateException.class,
                () -> PluginParameterSchemaSecurityPolicy.validate(
                        PluginRegistry.ZONE_PLATE, remote));
        assertTrue(remoteError.getMessage().contains("references are not supported"));

        ObjectNode local = zonePlateSchema();
        field(local, "focalLengthMm").put("$ref", "#/$defs/length");
        assertThrows(
                IllegalStateException.class,
                () -> PluginParameterSchemaSecurityPolicy.validate(
                        PluginRegistry.ZONE_PLATE, local));
    }

    @Test
    void rejectsUntrustedWidgetIdentifiers() {
        ObjectNode schema = zonePlateSchema();
        field(schema, "dpi").put("x-fresnel-widget", "https://example.invalid/widget.js");
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> PluginParameterSchemaSecurityPolicy.validate(
                        PluginRegistry.ZONE_PLATE, schema));
        assertTrue(error.getMessage().contains("untrusted widget"));
    }

    @Test
    void rejectsMalformedTypedAnnotations() {
        ObjectNode negativeStep = zonePlateSchema();
        field(negativeStep, "dpi").put("x-fresnel-step", -50);
        assertThrows(
                IllegalStateException.class,
                () -> PluginParameterSchemaSecurityPolicy.validate(
                        PluginRegistry.ZONE_PLATE, negativeStep));

        ObjectNode stringPowerOfTwo = zonePlateSchema();
        field(stringPowerOfTwo, "maskType").put("x-fresnel-power-of-two", true);
        assertThrows(
                IllegalStateException.class,
                () -> PluginParameterSchemaSecurityPolicy.validate(
                        PluginRegistry.ZONE_PLATE, stringPowerOfTwo));

        ObjectNode invalidLabels = zonePlateSchema();
        ObjectNode labels = (ObjectNode) field(invalidLabels, "maskType")
                .get("x-fresnel-enum-labels");
        labels.put("NOT_AN_ENUM_VALUE", "Unexpected");
        assertThrows(
                IllegalStateException.class,
                () -> PluginParameterSchemaSecurityPolicy.validate(
                        PluginRegistry.ZONE_PLATE, invalidLabels));
    }

    @Test
    void rejectsSchemaIdsThatDoNotMatchPluginAndVersion() {
        ObjectNode schema = zonePlateSchema();
        schema.put("$id", "https://example.invalid/wrong-plugin.schema.json");
        assertThrows(
                IllegalStateException.class,
                () -> PluginParameterSchemaSecurityPolicy.validate(
                        PluginRegistry.ZONE_PLATE, schema));
    }

    private ObjectNode zonePlateSchema() {
        return (ObjectNode) schemaService.requireByPluginId("zone-plate")
                .parameterSchema().deepCopy();
    }

    private static ObjectNode field(ObjectNode schema, String fieldName) {
        return (ObjectNode) schema.get("properties").get(fieldName);
    }
}
