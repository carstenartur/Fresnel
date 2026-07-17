package org.fresnel.backend.api;

import org.fresnel.optics.PluginCapability;
import org.fresnel.optics.PluginDescriptor;
import org.fresnel.optics.PluginEditorMode;
import org.fresnel.optics.PluginRegistry;
import org.fresnel.optics.PluginStabilityLevel;
import org.fresnel.optics.PropagationMode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** Read-only metadata and schema endpoints for registered Fresnel plugins. */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginSchemaService schemaService;

    public PluginController(PluginSchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PluginMetadata> listPlugins() {
        return PluginRegistry.ALL.stream()
                .map(PluginMetadata::from)
                .toList();
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PluginMetadata> getPlugin(@PathVariable("id") String id) {
        return PluginRegistry.findById(id)
                .map(PluginMetadata::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Returns the current versioned parameter and UI schemas for one plugin. */
    @GetMapping(value = "/{id}/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PluginSchemaDocument> getPluginSchema(@PathVariable("id") String id) {
        return schemaService.findByPluginId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Stable JSON view that does not expose classpath schema resource names. */
    public record PluginMetadata(
            String id,
            String displayName,
            String description,
            String rendererClass,
            String parameterType,
            String frontendModeId,
            String documentationUrl,
            PluginStabilityLevel stability,
            List<PluginCapability> capabilities,
            List<PropagationMode> propagationModes,
            int parameterSchemaVersion,
            PluginEditorMode editorMode,
            String schemaUrl,
            boolean supportsPrintabilityAnalysis,
            boolean supportsOpticalQualityReport,
            boolean supportsExperimentalValidation,
            boolean supportsPropagationPreview
    ) {
        static PluginMetadata from(PluginDescriptor descriptor) {
            return new PluginMetadata(
                    descriptor.id(),
                    descriptor.displayName(),
                    descriptor.description(),
                    descriptor.rendererClass(),
                    descriptor.parameterType(),
                    descriptor.frontendModeId(),
                    descriptor.documentationUrl(),
                    descriptor.stability(),
                    descriptor.capabilities().stream()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList(),
                    descriptor.propagationModes().stream()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList(),
                    descriptor.schema().parameterSchemaVersion(),
                    descriptor.schema().editorMode(),
                    "/api/plugins/" + descriptor.id() + "/schema",
                    descriptor.supportsPrintabilityAnalysis(),
                    descriptor.supportsOpticalQualityReport(),
                    descriptor.supportsExperimentalValidation(),
                    descriptor.supportsPropagationPreview()
            );
        }
    }
}
