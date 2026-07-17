package org.fresnel.backend.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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

        if (root.has("kind") && !root.has("format")) {
            return migrateLegacy(mapper.convertValue(root, DesignDocument.class));
        }
        return normalize(mapper.convertValue(root, FresnelJobDocument.class));
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
        if (legacy.payload() == null || legacy.payload().isNull()) {
            throw new IllegalArgumentException("Legacy design payload must not be empty");
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
                isBlank(job.schema()) ? FresnelJobDocument.SCHEMA_URL : job.schema().trim(),
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

        if (request instanceof SingleZonePlateRequest r) {
            SingleZonePlateRequest normalized = new SingleZonePlateRequest(
                    r.apertureDiameterMm(),
                    r.focalLengthMm(),
                    r.wavelengthNm(),
                    r.dpi(),
                    r.targetOffsetXmm() == null ? 0.0 : r.targetOffsetXmm(),
                    r.targetOffsetYmm() == null ? 0.0 : r.targetOffsetYmm(),
                    r.maskType() == null ? MaskType.BINARY_AMPLITUDE : r.maskType(),
                    r.polarity() == null ? Polarity.POSITIVE : r.polarity());
            return mapper.valueToTree(normalized);
        }
        return mapper.valueToTree(request);
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

    private FresnelJobDocument.ProductionPlan normalizeProduction(
            PluginDescriptor descriptor,
            FresnelJobDocument.ProductionPlan production) {
        if (production == null || production.outputs().isEmpty()) {
            return null;
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
            }
            if (output.printScale() != null
                    && (!Double.isFinite(output.printScale()) || output.printScale() <= 0.0)) {
                throw new IllegalArgumentException("production output printScale must be finite and positive");
            }
            if (output.options() != null && !output.options().isObject()) {
                throw new IllegalArgumentException("production output options must be a JSON object");
            }

            normalized.add(new FresnelJobDocument.ProductionOutput(
                    id,
                    format,
                    filename,
                    sheet,
                    output.printScale(),
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
