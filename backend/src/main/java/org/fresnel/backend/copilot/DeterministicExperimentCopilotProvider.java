package org.fresnel.backend.copilot;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic provider used by tests, offline demos and quota-constrained builds.
 *
 * <p>It deliberately recognises only the first Zone Plate vertical slice. Unknown
 * language is not interpreted creatively; genuinely missing wavelength/focal intent
 * is returned as a clarification question.</p>
 */
@Component
public final class DeterministicExperimentCopilotProvider implements ExperimentCopilotProvider {

    private static final Pattern WAVELENGTH = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*nm\\b");
    private static final Pattern DPI = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:dpi|dots?\\s+per\\s+inch)\\b");
    private static final Pattern APERTURE_AFTER_VALUE = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*mm\\s*(?:aperture|diameter)\\b");
    private static final Pattern APERTURE_BEFORE_VALUE = Pattern.compile(
            "(?i)(?:aperture|diameter)(?:\\s+of|\\s*=)?\\s*(\\d+(?:\\.\\d+)?)\\s*mm\\b");
    private static final Pattern FOCUS_AFTER_VALUE = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*(mm|millimet(?:er|re)s?|m|met(?:er|re)s?)\\s*"
                    + "(?:focal(?:\\s+(?:distance|length))?|focus)\\b");
    private static final Pattern FOCUS_BEFORE_VALUE = Pattern.compile(
            "(?i)(?:focal(?:\\s+(?:distance|length))?|focus)(?:\\s+of|\\s*=)?\\s*"
                    + "(\\d+(?:\\.\\d+)?)\\s*(mm|millimet(?:er|re)s?|m|met(?:er|re)s?)\\b");

    private final ObjectMapper mapper;

    public DeterministicExperimentCopilotProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public String displayName() {
        return "Deterministic Fresnel demo";
    }

    @Override
    public String modelId() {
        return "deterministic-zone-plate-parser/1";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExperimentProposal propose(ExperimentCopilotContext context) {
        String normalized = normalizeNumberWords(context.request());
        String lower = normalized.toLowerCase(Locale.ROOT);
        List<ExperimentProposal.Parameter> parameters = new ArrayList<>();
        List<String> questions = new ArrayList<>();

        OptionalDouble wavelength = firstNumber(WAVELENGTH, normalized);
        if (wavelength.isPresent()) {
            addNumber(parameters, "wavelengthNm", wavelength.getAsDouble(),
                    "Explicit wavelength read from the request.");
        } else {
            questions.add("Which design wavelength should Fresnel use, in nanometres?");
        }

        OptionalDouble focus = focusMillimetres(normalized);
        if (focus.isPresent()) {
            addNumber(parameters, "focalLengthMm", focus.getAsDouble(),
                    "Explicit focal distance converted to millimetres.");
        } else {
            questions.add("What focal distance should the design target?");
        }

        OptionalDouble dpi = firstNumber(DPI, normalized);
        dpi.ifPresent(value -> addNumber(parameters, "dpi", value,
                "Explicit fabrication resolution read from the request."));

        OptionalDouble aperture = firstNumber(APERTURE_AFTER_VALUE, normalized);
        if (aperture.isEmpty()) aperture = firstNumber(APERTURE_BEFORE_VALUE, normalized);
        aperture.ifPresent(value -> addNumber(parameters, "apertureDiameterMm", value,
                "Explicit aperture diameter read from the request."));

        if (lower.contains("greyscale phase") || lower.contains("grayscale phase")
                || lower.contains("phase mask") || lower.contains("phase zone plate")) {
            addText(parameters, "maskType", "GREYSCALE_PHASE",
                    "The request explicitly asks for a phase design.");
        } else if (lower.contains("binary") || lower.contains("amplitude")) {
            addText(parameters, "maskType", "BINARY_AMPLITUDE",
                    "The request explicitly asks for a binary/amplitude design.");
        }

        if (lower.contains("negative polarity") || lower.contains("inverted")) {
            addText(parameters, "polarity", "NEGATIVE",
                    "The request explicitly asks for an inverted/negative mask.");
        } else if (lower.contains("positive polarity")) {
            addText(parameters, "polarity", "POSITIVE",
                    "The request explicitly asks for positive polarity.");
        }

        List<ExperimentProposal.Alternative> alternatives = List.of(
                new ExperimentProposal.Alternative(
                        "Greyscale phase mask",
                        "Consider a phase mask when the fabrication process can reproduce calibrated phase relief.",
                        mapper.valueToTree(Map.of("maskType", "GREYSCALE_PHASE"))),
                new ExperimentProposal.Alternative(
                        "Binary amplitude mask",
                        "Use the simpler binary mask when robust printing and inspection are more important than efficiency.",
                        mapper.valueToTree(Map.of("maskType", "BINARY_AMPLITUDE")))
        );

        String summary = lower.contains("robust") || lower.contains("easy to fabricate")
                || lower.contains("printable")
                ? "Prepare a fabrication-oriented Zone Plate proposal and let deterministic Fresnel validation choose a printable aperture."
                : "Prepare a grounded Zone Plate proposal from the explicitly stated optical intent.";

        return new ExperimentProposal("zone-plate", parameters, questions, alternatives, summary);
    }

    private void addNumber(
            List<ExperimentProposal.Parameter> parameters,
            String path,
            double value,
            String rationale) {
        parameters.add(new ExperimentProposal.Parameter(
                path,
                mapper.valueToTree(value),
                ExperimentProposal.ValueSource.USER_SUPPLIED,
                rationale));
    }

    private void addText(
            List<ExperimentProposal.Parameter> parameters,
            String path,
            String value,
            String rationale) {
        parameters.add(new ExperimentProposal.Parameter(
                path,
                mapper.valueToTree(value),
                ExperimentProposal.ValueSource.USER_SUPPLIED,
                rationale));
    }

    private static OptionalDouble firstNumber(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return OptionalDouble.empty();
        return OptionalDouble.of(Double.parseDouble(matcher.group(1)));
    }

    private static OptionalDouble focusMillimetres(String text) {
        Matcher matcher = FOCUS_AFTER_VALUE.matcher(text);
        if (!matcher.find()) matcher = FOCUS_BEFORE_VALUE.matcher(text);
        if (!matcher.find(0)) return OptionalDouble.empty();
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        return OptionalDouble.of(unit.startsWith("m") && !unit.startsWith("mm")
                && !unit.startsWith("millimet") ? value * 1000.0 : value);
    }

    private static String normalizeNumberWords(String input) {
        return input
                .replaceAll("(?i)\\bone\\b", "1")
                .replaceAll("(?i)\\btwo\\b", "2")
                .replaceAll("(?i)\\bthree\\b", "3")
                .replaceAll("(?i)\\bfour\\b", "4")
                .replaceAll("(?i)\\bfive\\b", "5");
    }
}
