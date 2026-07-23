package org.fresnel.backend.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.fresnel.optics.AxisQuantity;
import org.fresnel.optics.GratingProgression;
import org.fresnel.optics.HologramParameters;
import org.fresnel.optics.LineOrientation;
import org.fresnel.optics.MaskType;
import org.fresnel.optics.PluginDescriptor;
import org.fresnel.optics.PluginRegistry;
import org.fresnel.optics.Polarity;
import org.fresnel.optics.ProgressionDirection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PluginSchemaServiceTest {

    private static final Map<String, Class<?>> REQUEST_TYPES = Map.of(
            "zone-plate", SingleZonePlateRequest.class,
            "variable-line-grating", VariableLineGratingRequest.class,
            "hex-macro-cell", HexMacroCellRequest.class,
            "window-foil", WindowFoilRequest.class,
            "multi-focus", MultiFocusRequest.class,
            "rgb-zone-plate", RgbZonePlateRequest.class,
            "hologram", HologramRequest.class);

    @Autowired PluginSchemaService service;
    @Autowired ObjectMapper mapper;
    @Autowired Validator validator;

    @Test
    void loadsOneSchemaDocumentForEveryRegisteredPluginInRegistryOrder() {
        assertEquals(
                PluginRegistry.ALL.stream().map(PluginDescriptor::id).toList(),
                service.all().stream().map(PluginSchemaDocument::pluginId).toList());
        assertEquals(7, service.all().size());

        for (PluginDescriptor descriptor : PluginRegistry.ALL) {
            PluginSchemaDocument document = service.requireByPluginId(descriptor.id());
            assertEquals(descriptor.schema().parameterSchemaVersion(),
                    document.parameterSchemaVersion());
            assertEquals(descriptor.schema().editorMode(), document.editorMode());
            assertEquals(PluginSchemaService.JSON_SCHEMA_DRAFT_2020_12,
                    document.parameterSchema().get("$schema").asText());
            assertEquals(descriptor.id(), document.uiSchema().get("pluginId").asText());
            assertFalse(document.capabilities().isEmpty());
        }
    }

    @Test
    void topLevelSchemaPropertiesMatchAcceptedBackendRequestRecords() {
        for (Map.Entry<String, Class<?>> entry : REQUEST_TYPES.entrySet()) {
            PluginSchemaDocument document = service.requireByPluginId(entry.getKey());
            Set<String> schemaProperties = propertyNames(
                    document.parameterSchema().get("properties"));
            Set<String> recordComponents = Arrays.stream(entry.getValue().getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            assertEquals(recordComponents, schemaProperties,
                    "schema/DTO drift for " + entry.getKey());
        }
    }

    @Test
    void everyPublishedDefaultDeserializesAndPassesStructuralBeanValidation() {
        for (Map.Entry<String, Class<?>> entry : REQUEST_TYPES.entrySet()) {
            PluginSchemaDocument document = service.requireByPluginId(entry.getKey());
            Object request = mapper.convertValue(document.defaults(), entry.getValue());
            Set<ConstraintViolation<Object>> violations = validator.validate(request);
            assertTrue(violations.isEmpty(),
                    () -> "invalid defaults for " + entry.getKey() + ": " + violations);
        }
    }

    @Test
    void publicEnumValuesMatchBackendEnums() {
        PluginSchemaDocument zonePlate = service.requireByPluginId("zone-plate");
        assertEquals(enumNames(MaskType.class), enumValues(zonePlate, "maskType"));
        assertEquals(enumNames(Polarity.class), enumValues(zonePlate, "polarity"));

        PluginSchemaDocument grating = service.requireByPluginId("variable-line-grating");
        assertEquals(enumNames(LineOrientation.class), enumValues(grating, "lineOrientation"));
        assertEquals(enumNames(GratingProgression.class), enumValues(grating, "progression"));
        assertEquals(enumNames(ProgressionDirection.class),
                enumValues(grating, "progressionDirection"));
        assertEquals(enumNames(AxisQuantity.class), enumValues(grating, "axisQuantity"));
        assertEquals(enumNames(Polarity.class), enumValues(grating, "polarity"));

        PluginSchemaDocument hologram = service.requireByPluginId("hologram");
        assertEquals(enumNames(HologramParameters.OutputType.class),
                enumValues(hologram, "outputType"));
    }

    @Test
    void hologramUiUsesAValidatedRadioAndSingleOperatorVisibilityCondition() {
        JsonNode ui = service.requireByPluginId("hologram").uiSchema();
        assertEquals("radio", ui.get("widgets").get("outputType").get("type").asText());

        JsonNode fabrication = ui.get("groups").get(2);
        JsonNode condition = fabrication.get("visibleWhen");
        assertEquals("fabrication", fabrication.get("id").asText());
        assertEquals("outputType", condition.get("path").asText());
        assertEquals("GREYSCALE_PHASE", condition.get("equals").asText());
        assertFalse(condition.has("notEquals"));
        assertFalse(condition.has("oneOf"));
    }

    @Test
    void variableLineGratingUiUsesAnExclusiveOrientationRadio() {
        JsonNode ui = service.requireByPluginId("variable-line-grating").uiSchema();
        assertEquals("radio", ui.get("widgets").get("lineOrientation").get("type").asText());
        assertEquals(2, service.requireByPluginId("variable-line-grating")
                .parameterSchema().get("properties").get("lineOrientation").get("enum").size());
    }

    @Test
    void callersReceiveDefensiveJsonCopies() {
        PluginSchemaDocument first = service.requireByPluginId("zone-plate");
        PluginSchemaDocument second = service.requireByPluginId("zone-plate");
        assertNotSame(first.parameterSchema(), second.parameterSchema());
        assertNotSame(first.uiSchema(), second.uiSchema());
        assertNotSame(first.defaults(), second.defaults());
        assertEquals(first.parameterSchema(), second.parameterSchema());
        assertEquals(first.uiSchema(), second.uiSchema());
        assertEquals(first.defaults(), second.defaults());
    }

    private static Set<String> propertyNames(JsonNode properties) {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            names.add(entry.getKey());
        }
        return names;
    }

    private static Set<String> enumValues(PluginSchemaDocument document, String field) {
        JsonNode values = document.parameterSchema()
                .get("properties").get(field).get("enum");
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode value : values) names.add(value.asText());
        return names;
    }

    private static Set<String> enumNames(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
