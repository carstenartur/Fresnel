package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Printing and measurement conditions for an experiment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentSetup(
        String printerModel,
        Double nominalDpi,
        Double effectiveDpi,
        String materialType,
        String exposureSettings,
        String lightSourceType,
        Double wavelengthNm,
        String spectrumEstimate,
        String environmentalNotes,
        List<String> photoReferences
) {
    public ExperimentSetup {
        photoReferences = photoReferences == null ? List.of() : List.copyOf(photoReferences);
    }
}
