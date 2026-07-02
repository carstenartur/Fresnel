package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.fresnel.optics.DesignValidationReport;

/**
 * Reproducible record of a printed / measured optical experiment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentRecord(
        String designId,
        String pluginId,
        String parameterHash,
        DesignDocument designDocument,
        DesignValidationReport validationReport,
        ExperimentSetup setup,
        MeasurementResult measurement,
        ExperimentalComparison comparison
) {}
