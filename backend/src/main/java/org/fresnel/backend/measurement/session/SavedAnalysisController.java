package org.fresnel.backend.measurement.session;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/measurement-sessions/{id}/analysis/{attempt}")
class SavedAnalysisController {
    private final MeasurementSessions sessions;
    SavedAnalysisController(MeasurementSessions sessions) { this.sessions = sessions; }

    @PutMapping
    MeasurementSessions.View start(Principal user, @PathVariable("id") UUID id, @PathVariable("attempt") UUID attempt,
            @RequestHeader(value="X-Previous-Analysis",required=false) UUID previous) throws IOException {
        return sessions.startAnalysis(user.getName(),id,attempt,previous);
    }

    @DeleteMapping
    MeasurementSessions.View stop(Principal user, @PathVariable("id") UUID id, @PathVariable("attempt") UUID attempt,
            @RequestParam(value="failed",defaultValue="false") boolean failed) throws IOException {
        return sessions.stopAnalysis(user.getName(),id,attempt,failed);
    }

    @PutMapping(value="/result",consumes="application/json")
    MeasurementSessions.View result(Principal user, @PathVariable("id") UUID id, @PathVariable("attempt") UUID attempt,
            @RequestHeader("X-Content-SHA256") String sha, HttpServletRequest request) throws IOException {
        return sessions.analysisResult(user.getName(),id,attempt,request.getContentType().split(";")[0],sha,request.getInputStream());
    }
}
