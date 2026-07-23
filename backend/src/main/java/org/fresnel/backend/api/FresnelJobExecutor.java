package org.fresnel.backend.api;

import org.fresnel.optics.DxfExporter;
import org.fresnel.optics.GerberExporter;
import org.fresnel.optics.HexMacroCellRenderer;
import org.fresnel.optics.HologramParameters;
import org.fresnel.optics.HologramSynthesizer;
import org.fresnel.optics.MaskType;
import org.fresnel.optics.MultiFocusRenderer;
import org.fresnel.optics.PclCompression;
import org.fresnel.optics.PclExporter;
import org.fresnel.optics.PdfExporter;
import org.fresnel.optics.PhaseReliefGenerator;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.PrinterRasterProfile;
import org.fresnel.optics.PrinterRasterProfiles;
import org.fresnel.optics.ReliefParameters;
import org.fresnel.optics.RenderResult;
import org.fresnel.optics.RgbZonePlateRenderer;
import org.fresnel.optics.SingleZonePlateParameters;
import org.fresnel.optics.StlExporter;
import org.fresnel.optics.SvgExporter;
import org.fresnel.optics.VariableLineGratingParameters;
import org.fresnel.optics.VariableLineGratingRenderer;
import org.fresnel.optics.VariableLineGratingSvgExporter;
import org.fresnel.optics.WindowFoilRenderer;
import org.fresnel.optics.ZonePlateRenderer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Executes the production plan of a canonical {@link FresnelJobDocument}. */
@Service
public class FresnelJobExecutor {

    private static final String PNG = "image/png";
    private static final String SVG = "image/svg+xml";
    private static final String PDF = "application/pdf";
    private static final String PCL = PclExporter.MEDIA_TYPE;
    private static final String DXF = "application/dxf";
    private static final String GERBER = "application/vnd.gerber";
    private static final String STL = "model/stl";
    private static final Set<String> HOLOGRAM_PNG_OPTION_FIELDS = Set.of("hologramPng");
    private static final Set<String> GRATING_PCL_OPTION_FIELDS = Set.of(
            "printerProfileId", "compression");

    private final FresnelJobService jobService;
    private final ObjectMapper mapper;

    public FresnelJobExecutor(FresnelJobService jobService, ObjectMapper mapper) {
        if (jobService == null) throw new IllegalArgumentException("jobService must not be null");
        if (mapper == null) throw new IllegalArgumentException("mapper must not be null");
        this.jobService = jobService;
        this.mapper = mapper;
    }

    public FresnelJobExecutionResult execute(byte[] jobBytes, FresnelJobOutputSink sink)
            throws IOException {
        return execute(jobService.parseAndNormalize(jobBytes), sink);
    }

    public FresnelJobExecutionResult execute(
            FresnelJobDocument job,
            FresnelJobOutputSink sink) throws IOException {
        if (sink == null) throw new IllegalArgumentException("output sink must not be null");
        FresnelJobDocument normalized = jobService.normalize(job);
        if (normalized.production() == null
                || normalized.production().outputs() == null
                || normalized.production().outputs().isEmpty()) {
            throw new IllegalArgumentException(
                    "Fresnel job execution requires a non-empty production plan");
        }
        List<GeneratedArtifact> artifacts = new ArrayList<>();
        for (FresnelJobDocument.ProductionOutput output : normalized.production().outputs()) {
            RenderedOutput rendered = render(normalized, output);
            GeneratedArtifact artifact = inspect(output, rendered);
            sink.write(artifact, rendered.content());
            artifacts.add(artifact);
        }
        return new FresnelJobExecutionResult(normalized, artifacts);
    }

    private RenderedOutput render(
            FresnelJobDocument job,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        return switch (job.plugin().id()) {
            case "zone-plate" -> renderZonePlate(job, output);
            case "variable-line-grating" -> renderVariableLineGrating(job, output);
            case "hex-macro-cell" -> renderHexMacroCell(job, output);
            case "window-foil" -> renderWindowFoil(job, output);
            case "multi-focus" -> renderMultiFocus(job, output);
            case "rgb-zone-plate" -> renderRgb(job, output);
            case "hologram" -> renderHologram(job, output);
            default -> throw new IllegalArgumentException(
                    "No production executor for plugin " + job.plugin().id());
        };
    }

    private RenderedOutput renderZonePlate(
            FresnelJobDocument job,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        requireNoOptions(output);
        SingleZonePlateRequest request = mapper.convertValue(
                job.parameters(), SingleZonePlateRequest.class);
        SingleZonePlateParameters parameters = request.toParameters();
        String format = output.format();
        return switch (format) {
            case "png" -> {
                RenderResult rendered = ZonePlateRenderer.render(parameters);
                yield new RenderedOutput(
                        PngExporter.toPngBytes(rendered, parameters.dpi()), PNG, parameters.dpi());
            }
            case "svg" -> {
                boolean vector = parameters.targetOffsetXmm() == 0.0
                        && parameters.targetOffsetYmm() == 0.0
                        && parameters.maskType() == MaskType.BINARY_AMPLITUDE;
                byte[] content = vector
                        ? SvgExporter.toSvgZonePlateBytes(parameters)
                        : SvgExporter.toSvgRasterBytes(
                                ZonePlateRenderer.render(parameters), parameters.dpi());
                yield new RenderedOutput(content, SVG, null);
            }
            case "pdf" -> new RenderedOutput(
                    PdfExporter.toPdfBytes(
                            scaledForPdf(ZonePlateRenderer.render(parameters), output),
                            sheet(output)),
                    PDF,
                    null);
            case "dxf" -> new RenderedOutput(DxfExporter.toDxfBytes(parameters), DXF, null);
            case "gerber" -> new RenderedOutput(
                    GerberExporter.toGerberBytes(parameters), GERBER, null);
            default -> throw unsupported(job.plugin().id(), format);
        };
    }

    private RenderedOutput renderVariableLineGrating(
            FresnelJobDocument job,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        VariableLineGratingRequest request = mapper.convertValue(
                job.parameters(), VariableLineGratingRequest.class);
        VariableLineGratingParameters parameters = request.toParameters();
        return switch (output.format()) {
            case "png" -> {
                requireNoOptions(output);
                RenderResult rendered = VariableLineGratingRenderer.render(parameters);
                yield new RenderedOutput(
                        PngExporter.toPngBytes(rendered, parameters.dpi()), PNG, parameters.dpi());
            }
            case "svg" -> {
                requireNoOptions(output);
                yield new RenderedOutput(
                        VariableLineGratingSvgExporter.toSvgBytes(parameters), SVG, null);
            }
            case "pdf" -> {
                requireNoOptions(output);
                yield new RenderedOutput(
                        PdfExporter.toPdfBytes(
                                scaledForPdf(VariableLineGratingRenderer.render(parameters), output),
                                sheet(output)),
                        PDF,
                        null);
            }
            case "pcl" -> renderVariableLineGratingPcl(parameters, output);
            default -> throw unsupported(job.plugin().id(), output.format());
        };
    }

    private static RenderedOutput renderVariableLineGratingPcl(
            VariableLineGratingParameters parameters,
            FresnelJobDocument.ProductionOutput output) {
        JsonNode options = output.options();
        if (options != null) {
            for (Map.Entry<String, JsonNode> entry : options.properties()) {
                if (!GRATING_PCL_OPTION_FIELDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Unsupported variable-line grating PCL option: " + entry.getKey());
                }
            }
        }
        String profileId = textOption(
                options, "printerProfileId", PrinterRasterProfiles.DEFAULT_PROFILE_ID);
        String compressionName = textOption(options, "compression", PclCompression.TIFF.name());
        final PclCompression compression;
        try {
            compression = PclCompression.valueOf(compressionName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "PCL compression must be NONE or TIFF", e);
        }
        PrinterRasterProfile profile = PrinterRasterProfiles.require(profileId);
        return new RenderedOutput(
                PclExporter.toPclBytes(parameters, profile, compression), PCL, null);
    }

    private RenderedOutput renderHexMacroCell(
            FresnelJobDocument job,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        requireNoOptions(output);
        HexMacroCellRequest request = mapper.convertValue(
                job.parameters(), HexMacroCellRequest.class);
        RenderResult rendered = HexMacroCellRenderer.render(request.toParameters());
        return switch (output.format()) {
            case "png" -> new RenderedOutput(
                    PngExporter.toPngBytes(rendered, request.dpi()), PNG, request.dpi());
            case "pdf" -> new RenderedOutput(
                    PdfExporter.toPdfBytes(scaledForPdf(rendered, output), sheet(output)), PDF, null);
            default -> throw unsupported(job.plugin().id(), output.format());
        };
    }

    private RenderedOutput renderWindowFoil(
            FresnelJobDocument job,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        requireNoOptions(output);
        WindowFoilRequest request = mapper.convertValue(
                job.parameters(), WindowFoilRequest.class);
        RenderResult rendered = WindowFoilRenderer.render(request.toParameters());
        return switch (output.format()) {
            case "png" -> new RenderedOutput(
                    PngExporter.toPngBytes(rendered, request.dpi()), PNG, request.dpi());
            case "pdf" -> new RenderedOutput(
                    PdfExporter.toPdfBytes(scaledForPdf(rendered, output), sheet(output)), PDF, null);
            default -> throw unsupported(job.plugin().id(), output.format());
        };
    }

    private RenderedOutput renderMultiFocus(
            FresnelJobDocument job,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        requireNoOptions(output);
        MultiFocusRequest request = mapper.convertValue(
                job.parameters(), MultiFocusRequest.class);
        if (!"png".equals(output.format())) throw unsupported(job.plugin().id(), output.format());
        RenderResult rendered = MultiFocusRenderer.render(request.toParameters());
        return new RenderedOutput(
                PngExporter.toPngBytes(rendered, request.dpi()), PNG, request.dpi());
    }

    private RenderedOutput renderRgb(
            FresnelJobDocument job,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        requireNoOptions(output);
        RgbZonePlateRequest request = mapper.convertValue(
                job.parameters(), RgbZonePlateRequest.class);
        if (!"png".equals(output.format())) throw unsupported(job.plugin().id(), output.format());
        SingleZonePlateParameters base = request.base().toParameters();
        RenderResult rendered = RgbZonePlateRenderer.render(
                base, request.redNm(), request.greenNm(), request.blueNm());
        return new RenderedOutput(
                PngExporter.toPngBytes(rendered, base.dpi()), PNG, base.dpi());
    }

    private RenderedOutput renderHologram(
            FresnelJobDocument job,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        HologramRequest request = mapper.convertValue(job.parameters(), HologramRequest.class);
        return switch (output.format()) {
            case "png" -> renderHologramPng(request, output);
            case "stl" -> {
                requireNoOptions(output);
                if (request.sidePx() > HologramController.MAX_STL_SIDE) {
                    throw new IllegalArgumentException(
                            "sidePx > " + HologramController.MAX_STL_SIDE
                                    + " is too large for STL export");
                }
                HologramRequest greyscale = new HologramRequest(
                        request.targetImageBase64(), request.sidePx(), request.iterations(),
                        HologramParameters.OutputType.GREYSCALE_PHASE, request.dpi(),
                        request.wavelengthNm(), request.refractiveIndexDelta(),
                        request.maxPhaseShiftRad());
                RenderResult mask = HologramSynthesizer.synthesize(
                        HologramController.decode(greyscale));
                ReliefParameters relief = new ReliefParameters(
                        request.resolvedWavelengthNm(),
                        request.resolvedRefractiveIndexDelta(),
                        request.resolvedMaxPhaseShiftRad());
                double[][] heightMap = PhaseReliefGenerator.toHeightMapMm(mask.image(), relief);
                yield new RenderedOutput(
                        StlExporter.toBinaryStl(heightMap, mask.pixelSizeMm()), STL, null);
            }
            default -> throw unsupported(job.plugin().id(), output.format());
        };
    }

    private RenderedOutput renderHologramPng(
            HologramRequest request,
            FresnelJobDocument.ProductionOutput output) throws IOException {
        HologramPngKind kind = hologramPngKind(output);
        if (kind == HologramPngKind.SOURCE) {
            return new RenderedOutput(
                    encodePng(decodeEmbeddedTarget(request.targetImageBase64())), PNG, null);
        }
        HologramParameters parameters = HologramController.decode(request);
        RenderResult mask = HologramSynthesizer.synthesize(parameters);
        if (kind == HologramPngKind.MASK) {
            return new RenderedOutput(
                    PngExporter.toPngBytes(mask, parameters.dpi()), PNG, parameters.dpi());
        }
        BufferedImage reconstruction = HologramSynthesizer.reconstruct(
                mask.image(), parameters.outputType());
        return new RenderedOutput(encodePng(reconstruction), PNG, null);
    }

    private static HologramPngKind hologramPngKind(
            FresnelJobDocument.ProductionOutput output) {
        JsonNode options = output.options();
        if (options == null || options.isEmpty()) return HologramPngKind.MASK;
        for (Map.Entry<String, JsonNode> entry : options.properties()) {
            if (!HOLOGRAM_PNG_OPTION_FIELDS.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Unsupported Hologram PNG option: " + entry.getKey());
            }
        }
        JsonNode value = options.get("hologramPng");
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Hologram PNG option hologramPng must be SOURCE, MASK or RECONSTRUCTION");
        }
        try {
            return HologramPngKind.valueOf(value.textValue().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Hologram PNG option hologramPng must be SOURCE, MASK or RECONSTRUCTION", e);
        }
    }

    private static String textOption(JsonNode options, String field, String defaultValue) {
        if (options == null || options.isEmpty() || !options.has(field)) return defaultValue;
        JsonNode value = options.get(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Production option " + field + " must be a non-empty string");
        }
        return value.textValue().trim();
    }

    private static BufferedImage decodeEmbeddedTarget(String encoded) throws IOException {
        if (encoded == null) throw new IllegalArgumentException("Hologram target image must not be null");
        int comma = encoded.indexOf(',');
        String base64 = encoded.startsWith("data:") && comma > 0
                ? encoded.substring(comma + 1)
                : encoded;
        if (base64.length() > HologramController.MAX_BASE64_BYTES) {
            throw new IllegalArgumentException("Hologram target image is too large");
        }
        final byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Hologram target image is not valid Base64", e);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
        if (image == null) throw new IllegalArgumentException("Hologram target image could not be decoded");
        return image;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG writer is available");
        }
        return output.toByteArray();
    }

    private static void requireNoOptions(FresnelJobDocument.ProductionOutput output) {
        if (output.options() != null && !output.options().isEmpty()) {
            throw new IllegalArgumentException(
                    "Production output options are not implemented for " + output.id());
        }
    }

    private static RenderResult scaledForPdf(
            RenderResult rendered,
            FresnelJobDocument.ProductionOutput output) {
        double scale = output.printScale() == null ? 1.0 : output.printScale();
        if (Double.compare(scale, 1.0) == 0) return rendered;
        return new RenderResult(rendered.image(), rendered.pixelSizeMm() * scale);
    }

    private static PdfExporter.SheetSize sheet(FresnelJobDocument.ProductionOutput output) {
        try {
            return PdfExporter.SheetSize.valueOf(output.sheet().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unsupported PDF sheet size: " + output.sheet(), e);
        }
    }

    private static IllegalArgumentException unsupported(String pluginId, String format) {
        return new IllegalArgumentException(
                "Production executor does not support " + format + " for plugin " + pluginId);
    }

    private static GeneratedArtifact inspect(
            FresnelJobDocument.ProductionOutput output,
            RenderedOutput rendered) throws IOException {
        Integer width = null;
        Integer height = null;
        if (PNG.equals(rendered.mediaType())) {
            BufferedImage image = decodePng(rendered.content());
            width = image.getWidth();
            height = image.getHeight();
        }
        return new GeneratedArtifact(
                output.id(),
                output.filename(),
                rendered.mediaType(),
                rendered.content().length,
                normalizedSha256(rendered.mediaType(), rendered.content(), rendered.dpi()),
                width,
                height,
                rendered.dpi());
    }

    public static String normalizedSha256(String mediaType, byte[] content, Double dpi)
            throws IOException {
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be empty");
        }
        if (content == null) throw new IllegalArgumentException("content must not be null");
        MessageDigest digest = sha256();
        if (PNG.equals(mediaType)) {
            BufferedImage image = decodePng(content);
            digest.update("fresnel-png-pixels-v1\0".getBytes(StandardCharsets.UTF_8));
            updateInt(digest, image.getWidth());
            updateInt(digest, image.getHeight());
            updateLong(digest, dpi == null ? 0L : Math.round(dpi / 25.4 * 1000.0));
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    updateInt(digest, image.getRGB(x, y));
                }
            }
        } else if (isTextMediaType(mediaType)) {
            digest.update("fresnel-text-v1\0".getBytes(StandardCharsets.UTF_8));
            String normalized = new String(content, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            digest.update(normalized.getBytes(StandardCharsets.UTF_8));
        } else {
            digest.update("fresnel-bytes-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(content);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isTextMediaType(String mediaType) {
        return SVG.equals(mediaType) || DXF.equals(mediaType) || GERBER.equals(mediaType)
                || mediaType.startsWith("text/");
    }

    private static BufferedImage decodePng(byte[] content) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        if (image == null) throw new IOException("Generated PNG could not be decoded");
        return image;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private enum HologramPngKind {
        SOURCE,
        MASK,
        RECONSTRUCTION
    }

    private record RenderedOutput(byte[] content, String mediaType, Double dpi) {
        private RenderedOutput {
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("generated artifact content must not be empty");
            }
        }
    }
}
