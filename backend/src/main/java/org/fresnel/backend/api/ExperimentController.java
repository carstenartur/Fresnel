package org.fresnel.backend.api;

import org.fresnel.optics.DesignValidationReport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Form-based experimental validation workflow.
 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private static final MediaType TEXT_MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final ObjectMapper mapper;

    public ExperimentController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostMapping(value = "/compare",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExperimentRecord compare(@RequestBody ExperimentRecord record) {
        return normalize(record);
    }

    @PostMapping(value = "/export.json",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportJson(@RequestBody ExperimentRecord record) throws Exception {
        ExperimentRecord normalized = normalize(record);
        byte[] body = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(normalized);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"fresnel-experiment-" + normalized.pluginId() + ".json\"");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PostMapping(value = "/export.md",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "text/markdown")
    public ResponseEntity<byte[]> exportMarkdown(@RequestBody ExperimentRecord record) {
        ExperimentRecord normalized = normalize(record);
        byte[] body = renderMarkdown(normalized).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(TEXT_MARKDOWN);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"fresnel-experiment-" + normalized.pluginId() + ".md\"");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static ExperimentRecord normalize(ExperimentRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("experiment record must not be null");
        }
        DesignDocument designDocument = validateDesignDocument(record.designDocument());
        DesignValidationReport validationReport = validateValidationReport(record.validationReport());
        String pluginId = defaulted(record.pluginId(), validationReport.pluginId(), "plugin id");
        if (!pluginId.equals(validationReport.pluginId())) {
            throw new IllegalArgumentException("experiment plugin id must match validation report plugin id");
        }
        String parameterHash = defaulted(record.parameterHash(), validationReport.parameterHash(), "parameter hash");
        if (!parameterHash.equals(validationReport.parameterHash())) {
            throw new IllegalArgumentException("experiment parameter hash must match validation report parameter hash");
        }
        ExperimentSetup setup = normalizeSetup(record.setup());
        MeasurementResult measurement = normalizeMeasurement(record.measurement(), validationReport);
        ExperimentalComparison comparison = compare(measurement);
        return new ExperimentRecord(
                trimToNull(record.designId()),
                pluginId,
                parameterHash,
                designDocument,
                validationReport,
                setup,
                measurement,
                comparison
        );
    }

    private static DesignDocument validateDesignDocument(DesignDocument doc) {
        if (doc == null) {
            throw new IllegalArgumentException("experiment design document must not be null");
        }
        if (!StringUtils.hasText(doc.kind()) || !DesignDocument.isKnownKind(doc.kind())) {
            throw new IllegalArgumentException("experiment design document kind is unknown");
        }
        if (doc.payload() == null || doc.payload().isNull()) {
            throw new IllegalArgumentException("experiment design document payload must not be empty");
        }
        return new DesignDocument(doc.kind(),
                doc.version() <= 0 ? DesignDocument.SCHEMA_VERSION : doc.version(),
                doc.payload());
    }

    private static DesignValidationReport validateValidationReport(DesignValidationReport report) {
        if (report == null) {
            throw new IllegalArgumentException("experiment validation report must not be null");
        }
        if (!StringUtils.hasText(report.pluginId())) {
            throw new IllegalArgumentException("experiment validation report plugin id must not be empty");
        }
        if (!StringUtils.hasText(report.parameterHash())) {
            throw new IllegalArgumentException("experiment validation report parameter hash must not be empty");
        }
        return report;
    }

    private static ExperimentSetup normalizeSetup(ExperimentSetup setup) {
        if (setup == null) {
            throw new IllegalArgumentException("experiment setup must not be null");
        }
        return new ExperimentSetup(
                trimToNull(setup.printerModel()),
                positiveOrNull(setup.nominalDpi(), "nominal DPI"),
                positiveOrNull(setup.effectiveDpi(), "effective DPI"),
                trimToNull(setup.materialType()),
                trimToNull(setup.exposureSettings()),
                trimToNull(setup.lightSourceType()),
                positiveOrNull(setup.wavelengthNm(), "measured wavelength"),
                trimToNull(setup.spectrumEstimate()),
                trimToNull(setup.environmentalNotes()),
                sanitizeStrings(setup.photoReferences())
        );
    }

    private static MeasurementResult normalizeMeasurement(MeasurementResult measurement,
                                                          DesignValidationReport validationReport) {
        if (measurement == null) {
            throw new IllegalArgumentException("measurement result must not be null");
        }
        Double targetFocalLengthMm = measurement.targetFocalLengthMm();
        if (targetFocalLengthMm == null && validationReport.targetFocalDistancesMm().size() == 1) {
            targetFocalLengthMm = validationReport.targetFocalDistancesMm().getFirst();
        }
        targetFocalLengthMm = positive(targetFocalLengthMm, "target focal length");

        List<MeasuredFocus> measuredFoci = measurement.measuredFoci().stream()
                .map(ExperimentController::normalizeMeasuredFocus)
                .toList();
        if (measuredFoci.isEmpty()) {
            throw new IllegalArgumentException("at least one measured focus is required");
        }
        if (measuredFoci.stream().noneMatch(f -> f.measuredFocalLengthMm() != null)) {
            throw new IllegalArgumentException("at least one measured focus must include a measured focal length");
        }
        return new MeasurementResult(targetFocalLengthMm, measuredFoci);
    }

    private static MeasuredFocus normalizeMeasuredFocus(MeasuredFocus focus) {
        if (focus == null) {
            throw new IllegalArgumentException("measured focus must not be null");
        }
        return new MeasuredFocus(
                trimToNull(focus.label()),
                positiveOrNull(focus.measuredFocalLengthMm(), "measured focal length"),
                positiveOrNull(focus.measuredSpotSizeMicrons(), "measured spot size"),
                trimToNull(focus.focusRating()),
                trimToNull(focus.notes())
        );
    }

    private static ExperimentalComparison compare(MeasurementResult measurement) {
        MeasuredFocus primary = measurement.measuredFoci().stream()
                .filter(f -> f.measuredFocalLengthMm() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "at least one measured focus must include a measured focal length"));
        double target = measurement.targetFocalLengthMm();
        double measured = primary.measuredFocalLengthMm();
        double errorMm = measured - target;
        double errorPercent = errorMm / target * 100.0;
        return new ExperimentalComparison(
                target,
                measured,
                errorMm,
                errorPercent,
                primary.measuredSpotSizeMicrons(),
                primary.focusRating(),
                summary(target, measured, errorMm, errorPercent, primary)
        );
    }

    private static String summary(double target, double measured, double errorMm,
                                  double errorPercent, MeasuredFocus focus) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "Measured focal length %.3f mm vs target %.3f mm (%+.2f mm, %+.2f%%).",
                measured, target, errorMm, errorPercent));
        if (focus.measuredSpotSizeMicrons() != null) {
            sb.append(String.format(Locale.ROOT, " Spot size: %.3f µm.", focus.measuredSpotSizeMicrons()));
        }
        if (StringUtils.hasText(focus.focusRating())) {
            sb.append(" Focus rating: ").append(focus.focusRating()).append('.');
        }
        return sb.toString();
    }

    private static String renderMarkdown(ExperimentRecord record) {
        StringBuilder md = new StringBuilder();
        md.append("# Experimental validation record\n\n");
        md.append("- Plugin: `").append(record.pluginId()).append("`\n");
        md.append("- Parameter hash: `").append(record.parameterHash()).append("`\n");
        md.append("- Design kind: `").append(record.designDocument().kind()).append("`\n");
        if (StringUtils.hasText(record.designId())) {
            md.append("- Design id: `").append(record.designId()).append("`\n");
        }
        md.append('\n');

        md.append("## Print and measurement setup\n\n");
        appendField(md, "Printer model", record.setup().printerModel());
        appendField(md, "Nominal DPI", formatNumber(record.setup().nominalDpi(), "dpi"));
        appendField(md, "Effective DPI", formatNumber(record.setup().effectiveDpi(), "dpi"));
        appendField(md, "Material / foil type", record.setup().materialType());
        appendField(md, "Exposure / print settings", record.setup().exposureSettings());
        appendField(md, "Light source", record.setup().lightSourceType());
        appendField(md, "Wavelength estimate", formatNumber(record.setup().wavelengthNm(), "nm"));
        appendField(md, "Spectrum estimate", record.setup().spectrumEstimate());
        appendField(md, "Environmental notes", record.setup().environmentalNotes());
        if (!record.setup().photoReferences().isEmpty()) {
            md.append("- Photo references:\n");
            for (String ref : record.setup().photoReferences()) {
                md.append("  - ").append(ref).append('\n');
            }
        }
        md.append('\n');

        md.append("## Measurements\n\n");
        md.append("- Target focal length: ").append(formatNumber(record.measurement().targetFocalLengthMm(), "mm")).append('\n');
        for (MeasuredFocus focus : record.measurement().measuredFoci()) {
            md.append("- Measured focus");
            if (StringUtils.hasText(focus.label())) {
                md.append(" (").append(focus.label()).append(')');
            }
            md.append(":\n");
            appendNestedField(md, "Measured focal length", formatNumber(focus.measuredFocalLengthMm(), "mm"));
            appendNestedField(md, "Measured spot size", formatNumber(focus.measuredSpotSizeMicrons(), "µm"));
            appendNestedField(md, "Focus rating", focus.focusRating());
            appendNestedField(md, "Notes", focus.notes());
        }
        md.append('\n');

        md.append("## Theory versus experiment\n\n");
        appendField(md, "Measured focal length", formatNumber(record.comparison().measuredFocalLengthMm(), "mm"));
        appendField(md, "Focal-length error", formatNumber(record.comparison().focalLengthErrorMm(), "mm"));
        appendField(md, "Focal-length error", formatNumber(record.comparison().focalLengthErrorPercent(), "%"));
        appendField(md, "Measured spot size", formatNumber(record.comparison().measuredSpotSizeMicrons(), "µm"));
        appendField(md, "Focus rating", record.comparison().focusRating());
        appendField(md, "Summary", record.comparison().summary());
        md.append('\n');

        md.append("## Validation snapshot\n\n");
        md.append("- Report valid: ").append(record.validationReport().valid() ? "yes" : "no").append('\n');
        if (!record.validationReport().targetFocalDistancesMm().isEmpty()) {
            md.append("- Predicted focal distances (mm): ");
            md.append(record.validationReport().targetFocalDistancesMm().stream()
                    .map(v -> String.format(Locale.ROOT, "%.3f", v))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
            md.append('\n');
        }
        return md.toString();
    }

    private static void appendField(StringBuilder md, String label, String value) {
        if (StringUtils.hasText(value)) {
            md.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static void appendNestedField(StringBuilder md, String label, String value) {
        if (StringUtils.hasText(value)) {
            md.append("  - ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static String formatNumber(Double value, String unit) {
        if (value == null) return null;
        String rendered = String.format(Locale.ROOT, "%.3f", value).replaceAll("\\.?0+$", "");
        return unit == null || unit.isBlank() ? rendered : rendered + " " + unit;
    }

    private static List<String> sanitizeStrings(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(ExperimentController::trimToNull)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static String defaulted(String value, String fallback, String label) {
        String resolved = StringUtils.hasText(value) ? value.trim() : fallback;
        if (!StringUtils.hasText(resolved)) {
            throw new IllegalArgumentException("experiment " + label + " must not be empty");
        }
        return resolved;
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) return null;
        return value.trim();
    }

    private static Double positiveOrNull(Double value, String label) {
        if (value == null) return null;
        return positive(value, label);
    }

    private static Double positive(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(label + " must be a positive finite number");
        }
        return value;
    }
}
