package org.fresnel.backend.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.fresnel.optics.HologramParameters;
import org.fresnel.optics.MaskType;
import org.fresnel.optics.PluginCapability;
import org.fresnel.optics.PluginDescriptor;
import org.fresnel.optics.PluginRegistry;
import org.fresnel.optics.Polarity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical parser, validator and serializer for {@link FresnelJobDocument}.
 *
 * <p>This service is deliberately independent of HTTP so that the same import path
 * can later be reused by desktop launch, CLI and documentation generation.</p>
 */
@Service
public class FresnelJobService {

    private static final Map<String, String> LEGACY_KIND_TO_PLUGIN = Map.of(
            DesignDocument.KIND_SINGLE, "zone-plate",
            DesignDocument.KIND_HEX, "hex-macro-cell",
            DesignDocument.KIND_FOIL, "window-foil",
            DesignDocument.KIND_MULTIFOCUS, "multi-focus",
            DesignDocument.KIND_RGB, "rgb-zone-plate",
            DesignDocument.KIND_HOLOGRAM, "hologram"
    );

    private static final Set<String> JOB_FIELDS = Set.of(
            "$schema", "format", "formatVersion", "plugin", "parameters", "production", "provenance");
    private static final Set<String> LEGACY_JOB_FIELDS = Set.of("kind", "version", "payload");
    private static final Set<String> PLUGIN_FIELDS = Set.of(
            "id", "parameterSchemaVersion", "algorithmVersion");
    private static final Set<String> PRODUCTION_FIELDS = Set.of("outputs");
    private static final Set<String> OUTPUT_FIELDS = Set.of(
            "id", "format", "filename", "sheet", "printScale", "options");
    private static final Set<String> PROVENANCE_FIELDS = Set.of(
            "createdWith", "applicationVersion", "parameterSha256");

    private static final Set<String> SINGLE_PARAMETER_FIELDS = Set.of(
            "apertureDiameterMm", "focalLengthMm", "wavelengthNm", "dpi",
            "targetOffsetXmm", "targetOffsetYmm", "maskType", "polarity");
    private static final Set<String> HEX_PARAMETER_FIELDS = Set.of(
            "macroRadiusMm", "subDiameterMm", "subPitchMm", "focalLengthMm",
            "targetOffsetXmm", "targetOffsetYmm", "wavelengthNm", "dpi",
            "maskType", "polarity");
    private static final Set<String> FOIL_PARAMETER_FIELDS = Set.of(
            "sheetWidthMm", "sheetHeightMm", "macroRadiusMm", "subDiameterMm",
            "subPitchMm", "wavelengthNm", "dpi", "maskType", "polarity",
            "cellSpecs", "drawCropMarks");
    private static final Set<String> MULTI_PARAMETER_FIELDS = Set.of(
            "apertureDiameterMm", "focusPoints", "wavelengthNm", "dpi", "maskType", "polarity");
    private static final Set<String> RGB_PARAMETER_FIELDS = Set.of("base", "redNm", "greenNm", "blueNm");
    private static final Set<String> HOLOGRAM_PARAMETER_FIELDS = Set.of(
            "targetImageBase64", "sidePx", "iterations", "outputType", "dpi",
            "wavelengthNm", "refractiveIndexDelta", "maxPhaseShiftRad");
    private static final Set<String> FOCUS_POINT_FIELDS = Set.of("xMm", "yMm", "zMm");
    private static final Set<String> CELL_SPEC_FIELDS = Set.of(
            "focalLengthMm", "targetOffsetXmm", "targetOffsetYmm");

    private static final Set<String> PDF_SHEETS = Set.of("FIT", "A4", "A3", "A2", "A1", "A0");

    private final ObjectMapper mapper;
    private final Validator validator;

    public FresnelJobService(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    /** Parse either a v1 Fresnel job or a legacy {@link DesignDocument}. */
    public FresnelJobDocument parseAndNormalize(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("Fresnel job must not be empty");
        }
        if (body.length > FresnelJobDocument.MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "Fresnel job exceeds the maximum size of " + FresnelJobDocument.MAX_FILE_BYTES + " bytes");
        }

        final JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Fresnel job JSON: " + conciseMessage(e), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Fresnel job root must be a JSON object");
        }

        boolean legacy = root.has("kind") && !root.has("format");
        validateEnvelopeShape(root, legacy);
        try {
            if (legacy) {
                return migrateLegacy(mapper.convertValue(root, DesignDocument.class));
            }
            return normalize(mapper.convertValue(root, FresnelJobDocument.class));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Fresnel job: " + conciseMessage(e), e);
        }
    }

    /** Serialize a normalized job with stable pretty printing. */
    public byte[] write(FresnelJobDocument job) {
        FresnelJobDocument normalized = normalize(job);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize Fresnel job: " + conciseMessage(e), e);
        }
    }

    /** Convert the former kind/version/payload envelope into the public job format. */
    public FresnelJobDocument migrateLegacy(DesignDocument legacy) {
        if (legacy == null) {
            throw new IllegalArgumentException("Legacy design document must not be null");
        }
        if (legacy.version() > DesignDocument.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Legacy design schema version " + legacy.version() + " is newer than supported ("
                            + DesignDocument.SCHEMA_VERSION + ")");
        }
        String pluginId = LEGACY_KIND_TO_PLUGIN.get(legacy.kind());
        if (pluginId == null) {
            throw new IllegalArgumentException("Unknown legacy design kind: " + legacy.kind());
        }
        if (legacy.payload() == null || !legacy.payload().isObject()) {
            throw new IllegalArgumentException("Legacy design payload must be a JSON object");
        }

        FresnelJobDocument converted = new FresnelJobDocument(
                FresnelJobDocument.SCHEMA_URL,
                FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION,
                new FresnelJobDocument.PluginRef(
                        pluginId,
                        FresnelJobDocument.CURRENT_PARAMETER_SCHEMA_VERSION,
                        pluginId + "/1"),
                legacy.payload(),
                null,
                new FresnelJobDocument.Provenance("Fresnel", applicationVersion(), null));
        return normalize(converted);
    }

    /** Validate and canonicalize a job without changing its design semantics. */
    public FresnelJobDocument normalize(FresnelJobDocument job) {
        if (job == null) {
            throw new IllegalArgumentException("Fresnel job must not be null");
        }
        if (!FresnelJobDocument.FORMAT_IDENTIFIER.equals(job.format())) {
            throw new IllegalArgumentException(
                    "Unsupported Fresnel job format: " + (job.format() == null ? "<missing>" : job.format()));
        }
        if (job.formatVersion() < 1) {
            throw new IllegalArgumentException("Fresnel job formatVersion must be at least 1");
        }
        if (job.formatVersion() > FresnelJobDocument.CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Fresnel job format version " + job.formatVersion() + " is newer than supported ("
                            + FresnelJobDocument.CURRENT_FORMAT_VERSION + "). Please upgrade Fresnel.");
        }
        if (job.plugin() == null || isBlank(job.plugin().id())) {
            throw new IllegalArgumentException("Fresnel job plugin.id must not be empty");
        }

        String pluginId = job.plugin().id().trim();
        PluginDescriptor descriptor = PluginRegistry.requireById(pluginId);
        int parameterSchemaVersion = job.plugin().parameterSchemaVersion() <= 0
                ? FresnelJobDocument.CURRENT_PARAMETER_SCHEMA_VERSION
                : job.plugin().parameterSchemaVersion();
        if (parameterSchemaVersion > FresnelJobDocument.CURRENT_PARAMETER_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Parameter schema version " + parameterSchemaVersion + " for plugin " + pluginId
                            + " is newer than supported ("
                            + FresnelJobDocument.CURRENT_PARAMETER_SCHEMA_VERSION + ")");
        }

        JsonNode parameters = validateAndNormalizeParameters(pluginId, job.parameters());
        FresnelJobDocument.ProductionPlan production = normalizeProduction(descriptor, job.production());
        String parameterHash = parameterSha256(parameters);

        FresnelJobDocument.Provenance supplied = job.provenance();
        FresnelJobDocument.Provenance provenance = new FresnelJobDocument.Provenance(
                supplied == null || isBlank(supplied.createdWith()) ? "Fresnel" : supplied.createdWith().trim(),
                supplied == null || isBlank(supplied.applicationVersion())
                        ? applicationVersion()
                        : supplied.applicationVersion().trim(),
                parameterHash);

        String algorithmVersion = isBlank(job.plugin().algorithmVersion())
                ? pluginId + "/1"
                : job.plugin().algorithmVersion().trim();

        return new FresnelJobDocument(
                FresnelJobDocument.SCHEMA_URL,
                FresnelJobDocument.FORMAT_IDENTIFIER,
                FresnelJobDocument.CURRENT_FORMAT_VERSION,
                new FresnelJobDocument.PluginRef(pluginId, parameterSchemaVersion, algorithmVersion),
                parameters,
                production,
                provenance);
    }

    private JsonNode validateAndNormalizeParameters(String pluginId, JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) {
            throw new IllegalArgumentException("Fresnel job parameters must be a JSON object");
        }
        validateParameterShape(pluginId, parameters);

        Class<?> requestType = switch (pluginId) {
            case "zone-plate" -> SingleZonePlateRequest.class;
            case "rgb-zone-plate" -> RgbZonePlateRequest.class;
            case "multi-focus" -> MultiFocusRequest.class;
            case "hex-macro-cell" -> HexMacroCellRequest.class;
            case "window-foil" -> WindowFoilRequest.class;
            case "hologram" -> HologramRequest.class;
            default -> throw new IllegalArgumentException("Unknown plugin id: " + pluginId);
        };

        final Object request;
        try {
            request = mapper.convertValue(parameters, requestType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid parameters for plugin " + pluginId + ": " + conciseMessage(e), e);
        }
        validateBean(pluginId, request);
        validateNestedBeans(pluginId, request);

        Object normalizedRequest;
        if (request instanceof SingleZonePlateRequest r) {
            normalizedRequest = normalizeSingle(r);
        } else if (request instanceof RgbZonePlateRequest r) {
            normalizedRequest = new RgbZonePlateRequest(normalizeSingle(r.base()), r.redNm(), r.greenNm(), r.blueNm());
        } else if (request instanceof HexMacroCellRequest r) {
            normalizedRequest = new HexMacroCellRequest(
                    r.macroRadiusMm(), r.subDiameterMm(), r.subPitchMm(), r.focalLengthMm(),
                    r.targetOffsetXmm() == null ? 0.0 : r.targetOffsetXmm(),
                    r.targetOffsetYmm() == null ? 0.0 : r.targetOffsetYmm(),
                    r.wavelengthNm(), r.dpi(),
                    r.maskType() == null ? MaskType.BINARY_AMPLITUDE : r.maskType(),
                    r.polarity() == null ? Polarity.POSITIVE : r.polarity());
        } else if (request instanceof MultiFocusRequest r) {
            normalizedRequest = new MultiFocusRequest(
                    r.apertureDiameterMm(), List.copyOf(r.focusPoints()), r.wavelengthNm(), r.dpi(),
                    r.maskType() == null ? MaskType.BINARY_AMPLITUDE : r.maskType(),
                    r.polarity() == null ? Polarity.POSITIVE : r.polarity());
        } else if (request instanceof WindowFoilRequest r) {
            List<WindowFoilRequest.CellSpecDto> specs = r.cellSpecs() == null
                    ? List.of()
                    : r.cellSpecs().stream()
                    .map(spec -> new WindowFoilRequest.CellSpecDto(
                            spec.focalLengthMm(),
                            spec.targetOffsetXmm() == null ? 0.0 : spec.targetOffsetXmm(),
                            spec.targetOffsetYmm() == null ? 0.0 : spec.targetOffsetYmm()))
                    .toList();
            normalizedRequest = new WindowFoilRequest(
                    r.sheetWidthMm(), r.sheetHeightMm(), r.macroRadiusMm(),
                    r.subDiameterMm(), r.subPitchMm(), r.wavelengthNm(), r.dpi(),
                    r.maskType() == null ? MaskType.BINARY_AMPLITUDE : r.maskType(),
                    r.polarity() == null ? Polarity.POSITIVE : r.polarity(),
                    specs, Boolean.TRUE.equals(r.drawCropMarks()));
        } else if (request instanceof HologramRequest r) {
            normalizedRequest = new HologramRequest(
                    r.targetImageBase64(), r.sidePx(), r.iterations(),
                    r.outputType() == null
                            ? HologramParameters.OutputType.GREYSCALE_PHASE
                            : r.outputType(),
                    r.dpi(),
                    r.resolvedWavelengthNm(),
                    r.resolvedRefractiveIndexDelta(),
                    r.resolvedMaxPhaseShiftRad());
        } else {
            throw new IllegalArgumentException("No parameter normalizer for plugin " + pluginId);
        }
        return mapper.valueToTree(normalizedRequest);
    }

    private static SingleZonePlateRequest normalizeSingle(SingleZonePlateRequest r) {
        return new SingleZonePlateRequest(
                r.apertureDiameterMm(),
                r.focalLengthMm(),
                r.wavelengthNm(),
                r.dpi(),
                r.targetOffsetXmm() == null ? 0.0 : r.targetOffsetXmm(),
                r.targetOffsetYmm() == null ? 0.0 : r.targetOffsetYmm(),
                r.maskType() == null ? MaskType.BINARY_AMPLITUDE : r.maskType(),
                r.polarity() == null ? Polarity.POSITIVE : r.polarity());
    }

    private void validateBean(String pluginId, Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return;
        }
        String details = violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        throw new IllegalArgumentException("Invalid parameters for plugin " + pluginId + ": " + details);
    }

    private void validateNestedBeans(String pluginId, Object request) {
        if (request instanceof MultiFocusRequest multi && multi.focusPoints() != null) {
            for (int i = 0; i < multi.focusPoints().size(); i++) {
                validateBean(pluginId + ".focusPoints[" + i + "]", multi.focusPoints().get(i));
            }
        }
        if (request instanceof WindowFoilRequest foil && foil.cellSpecs() != null) {
            for (int i = 0; i < foil.cellSpecs().size(); i++) {
                validateBean(pluginId + ".cellSpecs[" + i + "]", foil.cellSpecs().get(i));
            }
        }
    }

    private void validateEnvelopeShape(JsonNode root, boolean legacy) {
        if (legacy) {
            rejectUnknownFields(root, LEGACY_JOB_FIELDS, "legacy job");
            return;
        }

        rejectUnknownFields(root, JOB_FIELDS, "job");
        rejectUnknownFields(root.get("plugin"), PLUGIN_FIELDS, "plugin");
        rejectUnknownFields(root.get("provenance"), PROVENANCE_FIELDS, "provenance");

        JsonNode production = root.get("production");
        if (production == null || production.isNull()) {
            return;
        }
        rejectUnknownFields(production, PRODUCTION_FIELDS, "production");
        JsonNode outputs = production.get("outputs");
        if (outputs != null && !outputs.isNull()) {
            if (!outputs.isArray()) {
                throw new IllegalArgumentException("production.outputs must be a JSON array");
            }
            for (int i = 0; i < outputs.size(); i++) {
                rejectUnknownFields(outputs.get(i), OUTPUT_FIELDS, "production.outputs[" + i + "]");
            }
        }
    }

    private void validateParameterShape(String pluginId, JsonNode parameters) {
        Set<String> fields = switch (pluginId) {
            case "zone-plate" -> SINGLE_PARAMETER_FIELDS;
            case "hex-macro-cell" -> HEX_PARAMETER_FIELDS;
            case "window-foil" -> FOIL_PARAMETER_FIELDS;
            case "multi-focus" -> MULTI_PARAMETER_FIELDS;
            case "rgb-zone-plate" -> RGB_PARAMETER_FIELDS;
            case "hologram" -> HOLOGRAM_PARAMETER_FIELDS;
            default -> throw new IllegalArgumentException("Unknown plugin id: " + pluginId);
        };
        rejectUnknownFields(parameters, fields, "parameters");

        switch (pluginId) {
            case "rgb-zone-plate" ->
                    rejectUnknownFields(parameters.get("base"), SINGLE_PARAMETER_FIELDS, "parameters.base");
            case "multi-focus" ->
                    rejectArrayObjectFields(parameters.get("focusPoints"), FOCUS_POINT_FIELDS, "parameters.focusPoints");
            case "window-foil" ->
                    rejectArrayObjectFields(parameters.get("cellSpecs"), CELL_SPEC_FIELDS, "parameters.cellSpecs");
            default -> {
                // No nested parameter object for this plugin.
            }
        }
    }

    private static void rejectArrayObjectFields(JsonNode array, Set<String> fields, String path) {
        if (array == null || array.isNull()) {
            return;
        }
        if (!array.isArray()) {
            throw new IllegalArgumentException(path + " must be a JSON array");
        }
        for (int i = 0; i < array.size(); i++) {
            rejectUnknownFields(array.get(i), fields, path + "[" + i + "]");
        }
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowed, String path) {
        if (object == null || object.isNull()) {
            return;
        }
        if (!object.isObject()) {
            throw new IllegalArgumentException(path + " must be a JSON object");
        }
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            if (!allowed.contains(property.getKey())) {
                throw new IllegalArgumentException(
                        "Unknown field " + path + "." + property.getKey() + " for Fresnel job v1");
            }
        }
    }

    private FresnelJobDocument.ProductionPlan normalizeProduction(
            PluginDescriptor descriptor,
            FresnelJobDocument.ProductionPlan production) {
        if (production == null) {
            return null;
        }
        if (production.outputs() == null || production.outputs().isEmpty()) {
            throw new IllegalArgumentException("production.outputs must contain at least one output");
        }

        List<FresnelJobDocument.ProductionOutput> normalized = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> filenames = new HashSet<>();

        for (int i = 0; i < production.outputs().size(); i++) {
            FresnelJobDocument.ProductionOutput output = production.outputs().get(i);
            if (output == null) {
                throw new IllegalArgumentException("production.outputs[" + i + "] must not be null");
            }
            String format = normalizeFormat(output.format());
            PluginCapability capability = capabilityFor(format);
            if (!descriptor.supportsExport(capability)) {
                throw new IllegalArgumentException(
                        "Plugin " + descriptor.id() + " does not support " + format + " export");
            }

            String id = isBlank(output.id()) ? "output-" + (i + 1) : output.id().trim();
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate production output id: " + id);
            }

            String filename = isBlank(output.filename())
                    ? descriptor.id() + "-" + id + "." + extensionFor(format)
                    : output.filename().trim();
            validatePortableFilename(filename);
            if (!filenames.add(filename.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate production output filename: " + filename);
            }

            String sheet = output.sheet();
            if (sheet != null) {
                sheet = sheet.trim().toUpperCase(Locale.ROOT);
                if (!"pdf".equals(format)) {
                    throw new IllegalArgumentException("PDF sheet option is only valid for PDF outputs");
                }
                if (!PDF_SHEETS.contains(sheet)) {
                    throw new IllegalArgumentException("Unsupported PDF sheet size: " + sheet);
                }
            } else if ("pdf".equals(format)) {
                sheet = "FIT";
            }

            Double printScale = output.printScale();
            if (printScale != null && (!Double.isFinite(printScale) || printScale <= 0.0)) {
                throw new IllegalArgumentException("production output printScale must be finite and positive");
            }
            if (printScale == null && "pdf".equals(format)) {
                printScale = 1.0;
            }
            if (output.options() != null && !output.options().isObject()) {
                throw new IllegalArgumentException("production output options must be a JSON object");
            }

            normalized.add(new FresnelJobDocument.ProductionOutput(
                    id,
                    format,
                    filename,
                    sheet,
                    printScale,
                    output.options()));
        }
        return new FresnelJobDocument.ProductionPlan(normalized);
    }

    private static String normalizeFormat(String raw) {
        if (isBlank(raw)) {
            throw new IllegalArgumentException("production output format must not be empty");
        }
        String format = raw.trim().toLowerCase(Locale.ROOT);
        return "gbr".equals(format) ? "gerber" : format;
    }

    private static PluginCapability capabilityFor(String format) {
        return switch (format) {
            case "png" -> PluginCapability.EXPORT_PNG;
            case "svg" -> PluginCapability.EXPORT_SVG;
            case "pdf" -> PluginCapability.EXPORT_PDF;
            case "dxf" -> PluginCapability.EXPORT_DXF;
            case "gerber" -> PluginCapability.EXPORT_GERBER;
            case "stl" -> PluginCapability.EXPORT_STL;
            default -> throw new IllegalArgumentException("Unsupported production output format: " + format);
        };
    }

    private static String extensionFor(String format) {
        return "gerber".equals(format) ? "gbr" : format;
    }

    private static void validatePortableFilename(String filename) {
        if (filename.length() > 255) {
            throw new IllegalArgumentException("Production output filename is too long");
        }
        if (filename.equals(".") || filename.equals("..")
                || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0
                || filename.indexOf(':') >= 0
                || filename.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "Production output filename must be a portable basename without path components: " + filename);
        }
    }

    /** Stable hash of normalized JSON values, independent of object key order and 1 vs 1.0 spelling. */
    public String parameterSha256(JsonNode parameters) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(parameters, canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void appendCanonical(JsonNode node, StringBuilder target) {
        if (node == null || node.isNull()) {
            target.append("null");
        } else if (node.isObject()) {
            target.append('{');
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey());
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) target.append(',');
                appendQuoted(entries.get(i).getKey(), target);
                target.append(':');
                appendCanonical(entries.get(i).getValue(), target);
            }
            target.append('}');
        } else if (node.isArray()) {
            target.append('[');
            int index = 0;
            for (JsonNode value : node) {
                if (index++ > 0) target.append(',');
                appendCanonical(value, target);
            }
            target.append(']');
        } else if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            target.append(value.compareTo(BigDecimal.ZERO) == 0
                    ? "0"
                    : value.stripTrailingZeros().toPlainString());
        } else if (node.isTextual()) {
            appendQuoted(node.textValue(), target);
        } else if (node.isBoolean()) {
            target.append(node.booleanValue() ? "true" : "false");
        } else {
            throw new IllegalArgumentException("Unsupported JSON value in parameters: " + node.getNodeType());
        }
    }

    private void appendQuoted(String value, StringBuilder target) {
        try {
            target.append(mapper.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not canonicalize JSON string", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String applicationVersion() {
        String version = FresnelJobService.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static String conciseMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }
}
