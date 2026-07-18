package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignValidationReportContractTest {

    @Test
    void derivesValidTrueWhenNoErrorFindingExists() {
        DesignValidationReport report = report(List.of(
                new ValidationFinding(
                        ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "WARNING_ONLY",
                        "This is advisory.",
                        ValidationSeverity.WARNING)));

        assertTrue(report.valid());
    }

    @Test
    void derivesValidFalseWhenAnyErrorFindingExists() {
        DesignValidationReport report = report(List.of(
                new ValidationFinding(
                        ValidationLayer.NUMERICAL_PROPAGATION,
                        "INFORMATION",
                        "Informational context.",
                        ValidationSeverity.INFO),
                new ValidationFinding(
                        ValidationLayer.ANALYTICAL_OPTICS,
                        "INVALID_DESIGN",
                        "The design cannot be produced.",
                        ValidationSeverity.ERROR)));

        assertFalse(report.valid());
    }

    @Test
    void canonicalConstructorCannotBeGivenAContradictoryFlag() {
        DesignValidationReport report = new DesignValidationReport(
                "zone-plate",
                "hash",
                Map.of(),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(new ValidationFinding(
                        ValidationLayer.ANALYTICAL_OPTICS,
                        "ERROR",
                        "Still an error.",
                        ValidationSeverity.ERROR)),
                true);

        assertFalse(report.valid());
    }

    private static DesignValidationReport report(List<ValidationFinding> findings) {
        return new DesignValidationReport(
                "test-plugin",
                "hash",
                Map.of(),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                findings);
    }
}
