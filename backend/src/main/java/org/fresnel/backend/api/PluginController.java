package org.fresnel.backend.api;

import org.fresnel.optics.PluginCapability;
import org.fresnel.optics.PluginDescriptor;
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

/**
 * Read-only metadata endpoint for all registered Fresnel plugins.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/plugins} — list all plugin descriptors</li>
 *   <li>{@code GET /api/plugins/{id}} — get a single descriptor by plugin id</li>
 * </ul>
 *
 * <p>Responses contain plain JSON-serialisable views of {@link PluginDescriptor}.
 * The controller does not expose internal class types directly; it maps to simple
 * records so that the JSON shape is stable and independent of the optics-core model.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

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

    // ---- JSON view ----

    /**
     * JSON-serialisable view of a {@link PluginDescriptor}.
     *
     * <p>Using a dedicated view record keeps the HTTP response contract decoupled
     * from the optics-core model, making future additions non-breaking.
     *
     * <p>{@code capabilities} and {@code propagationModes} are returned as sorted
     * lists (alphabetical by name) so that the JSON array order is deterministic
     * across JDK versions and runs.
     */
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
            boolean supportsPrintabilityAnalysis,
            boolean supportsOpticalQualityReport,
            boolean supportsExperimentalValidation,
            boolean supportsPropagationPreview
    ) {
        static PluginMetadata from(PluginDescriptor d) {
            return new PluginMetadata(
                    d.id(),
                    d.displayName(),
                    d.description(),
                    d.rendererClass(),
                    d.parameterType(),
                    d.frontendModeId(),
                    d.documentationUrl(),
                    d.stability(),
                    d.capabilities().stream()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList(),
                    d.propagationModes().stream()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList(),
                    d.supportsPrintabilityAnalysis(),
                    d.supportsOpticalQualityReport(),
                    d.supportsExperimentalValidation(),
                    d.supportsPropagationPreview()
            );
        }
    }
}
