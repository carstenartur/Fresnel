package org.fresnel.backend.measurement.session;

import org.fresnel.backend.api.FresnelJobDocument;
import org.fresnel.backend.api.FresnelJobService;
import org.fresnel.optics.PluginRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
class ExperimentDocuments {
    private final FresnelJobService jobs;
    ExperimentDocuments(FresnelJobService jobs) { this.jobs = jobs; }

    byte[] job(MeasurementSessions.Plan plan) {
        JsonNode parameters = plan.parameters();
        if ("background-oriented-schlieren".equals(plan.pluginId())) parameters = parameters.get("targetParameters");
        else if (parameters.has("geometry")) parameters = parameters.get("geometry");
        // A manually photographed external or historical target can have no Fresnel generator settings.
        if (parameters == null || !parameters.isObject() || parameters.isEmpty()) return null;
        var descriptor = PluginRegistry.findById(plan.pluginId());
        if (descriptor.isEmpty()) return null;
        return jobs.write(new FresnelJobDocument(FresnelJobDocument.SCHEMA_URL, FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION, new FresnelJobDocument.PluginRef(plan.pluginId(),
                FresnelJobDocument.CURRENT_PARAMETER_SCHEMA_VERSION, descriptor.get().algorithmVersion()), parameters, null, null));
    }

    void checkJob(byte[] supplied, MeasurementSessions.Plan plan) {
        var parsed = jobs.parseAndNormalize(supplied);
        var expected = job(plan);
        if (expected == null || parsed.production() != null && parsed.production().outputs() != null && !parsed.production().outputs().isEmpty()
                || !parsed.plugin().id().equals(plan.pluginId()) || !parsed.parameters().equals(jobs.parseAndNormalize(expected).parameters()))
            throw MeasurementSessions.bad("Target job and measurement plan differ");
    }

    byte[] report(MeasurementSessions.Plan plan, Map<String,MeasurementSessions.Asset> assets, boolean hasJob) {
        boolean bos = "background-oriented-schlieren".equals(plan.pluginId());
        var s = new StringBuilder("# ").append(bos ? "Background-oriented Schlieren" : "Display photometric stereo")
                .append(" experiment\n\n");
        s.append(bos ? "This experiment estimates apparent image-plane displacement in pixels. Airflow direction, temperature and velocity require independent calibration.\n\n"
                : "This experiment estimates diffuse surface normals and relative albedo. Relief uses relative units unless an operator assigns a scale.\n\n");
        long sources = plan.steps().stream().filter(assets::containsKey).count();
        s.append("Captured steps: ").append(sources).append(" of ").append(plan.steps().size()).append(".\n\n");
        if (hasJob) s.append("Open `job.fresnel` to reproduce the target or illumination geometry.\n\n");
        s.append("| Capture step | Dimensions | Format | Bytes | SHA-256 |\n|---|---|---|---:|---|\n");
        for (String step : plan.steps()) {
            var a = assets.get(step);
            s.append('|').append(step).append('|');
            if (a == null) s.append("Pending||||\n");
            else s.append(a.width()).append(" × ").append(a.height()).append('|').append(a.mediaType()).append('|')
                    .append(a.byteLength()).append('|').append(a.sha256()).append("|\n");
        }
        var result = assets.get("result");
        if (result != null) s.append("\nAnalysis data: `results/result.json` (").append(result.byteLength())
                .append(" bytes, SHA-256 `").append(result.sha256()).append("`).\n");
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }
}
