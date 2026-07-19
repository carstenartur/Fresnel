package org.fresnel.backend.copilot;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicExperimentCopilotProviderTest {

    private final DeterministicExperimentCopilotProvider provider =
            new DeterministicExperimentCopilotProvider(new ObjectMapper());

    @Test
    void parsesHackathonVerticalSliceWithoutExternalCalls() {
        ExperimentProposal proposal = provider.propose(new ExperimentCopilotContext(
                "Create a 10 mm zone plate for 532 nm light with a one metre focus at 1200 DPI. "
                        + "Prefer a robust design that is easy to fabricate.",
                new ObjectMapper().createObjectNode(),
                new ObjectMapper().createObjectNode(),
                null));

        assertEquals("zone-plate", proposal.selectedPluginId());
        assertTrue(proposal.unresolvedQuestions().isEmpty());
        assertNumber(proposal, "apertureDiameterMm", 10.0);
        assertNumber(proposal, "wavelengthNm", 532.0);
        assertNumber(proposal, "focalLengthMm", 1000.0);
        assertNumber(proposal, "dpi", 1200.0);
    }

    @Test
    void asksOnlyForMissingCoreOpticalIntent() {
        ExperimentProposal proposal = provider.propose(new ExperimentCopilotContext(
                "Create a robust printable zone plate at 1200 DPI.",
                new ObjectMapper().createObjectNode(),
                new ObjectMapper().createObjectNode(),
                null));

        assertEquals(2, proposal.unresolvedQuestions().size());
        assertTrue(proposal.unresolvedQuestions().stream().anyMatch(q -> q.contains("wavelength")));
        assertTrue(proposal.unresolvedQuestions().stream().anyMatch(q -> q.contains("focal")));
    }

    private static void assertNumber(ExperimentProposal proposal, String path, double expected) {
        ExperimentProposal.Parameter parameter = proposal.parameters().stream()
                .filter(item -> path.equals(item.path()))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, parameter.value().doubleValue(), 1e-9);
        assertEquals(ExperimentProposal.ValueSource.USER_SUPPLIED, parameter.source());
    }
}
