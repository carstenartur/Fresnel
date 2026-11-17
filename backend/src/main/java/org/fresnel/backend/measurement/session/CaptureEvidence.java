package org.fresnel.backend.measurement.session;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Public acquisition evidence. The provider grant, host, device IDs and replay keys stay in the private journal. */
record CaptureEvidence(String provider, String sequenceId, boolean exposureLock, boolean focusLock,
                       boolean manualLocksConfirmed, List<Exposure> exposures) {
    static final String FILE = "capture-provenance.json";
    record Exposure(String step, String pixelSha256, String sourceSha256, Instant capturedAt) {}

    static CaptureEvidence read(Path directory, ObjectMapper json) throws IOException {
        Path journal = directory.resolve("photometry-capture.json");
        if (Files.isRegularFile(journal)) {
            var capture = json.readValue(Files.readAllBytes(journal),PhotometryCapture.Journal.class);
            var exposures = new ArrayList<Exposure>();
            for (var step : capture.plan.steps()) {
                var accepted = capture.accepted.get(step.id());
                if (accepted != null) exposures.add(new Exposure(step.id(), step.externalPatternSha256(),
                        accepted.asset().sha256(), accepted.asset().capturedAt()));
            }
            var settings = capture.plan.requirements();
            return new CaptureEvidence(PhotometryCapture.PROVIDER, capture.sequenceId, settings.lockExposure(),
                    settings.lockFocus(), capture.manualLocksConfirmed, List.copyOf(exposures));
        }
        Path restored = directory.resolve(FILE);
        return Files.isRegularFile(restored) ? json.readValue(Files.readAllBytes(restored),CaptureEvidence.class) : null;
    }

    void verify(MeasurementSessions.Plan plan, Map<String,MeasurementSessions.Asset> assets) {
        if (!PhotometryCapture.PLUGIN.equals(plan.pluginId()) || !PhotometryCapture.PROVIDER.equals(provider)
                || sequenceId == null || !sequenceId.matches("[0-9a-f]{64}") || exposures == null
                || exposures.size() > plan.steps().size()) throw MeasurementSessions.bad("Invalid capture provenance");
        var seen = new HashSet<String>();
        for (var e : exposures) {
            if (e == null || !plan.steps().contains(e.step) || !seen.add(e.step) || e.capturedAt == null
                    || e.pixelSha256 == null || !e.pixelSha256.matches("[0-9a-f]{64}")
                    || assets.get(e.step) == null || !assets.get(e.step).sha256().equals(e.sourceSha256))
                throw MeasurementSessions.bad("Capture provenance disagrees with source assets");
        }
    }
}
