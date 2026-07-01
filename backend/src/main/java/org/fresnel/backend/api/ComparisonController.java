package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.optics.DesignValidator;
import org.fresnel.optics.OpticalQualityReport;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.fresnel.optics.RgbZonePlateRenderer;
import org.fresnel.optics.SingleZonePlateParameters;
import org.fresnel.optics.ValidationResult;
import org.fresnel.optics.ZonePlateRenderer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for comparing two or more diffractive design variants.
 *
 * <p>{@code POST /api/designs/compare} accepts a {@link DesignComparisonRequest} containing
 * at least two {@link DesignVariant} objects and returns a {@link DesignComparisonResult} with:
 * <ul>
 *   <li>per-variant validation reports, quality metrics and printability warnings</li>
 *   <li>rendered preview images encoded as base-64 PNG at identical physical scale</li>
 *   <li>a structured list of parameters that differ across variants</li>
 *   <li>optional deterministic ranking when {@code rank=true} is requested</li>
 * </ul>
 *
 * <p>Supported plugin IDs: {@code "single"} (Zone Plate) and {@code "rgb"} (RGB Zone Plate).
 */
@RestController
@RequestMapping("/api/designs")
public class ComparisonController {

    /**
     * Maximum side length (px) for comparison preview images.
     * Kept intentionally smaller than the single-design preview cap so that
     * base-64 payloads remain manageable even when many variants are compared.
     */
    static final int MAX_COMPARISON_PREVIEW_PX = 512;

    @PostMapping(value = "/compare",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DesignComparisonResult compare(@Valid @RequestBody DesignComparisonRequest req)
            throws IOException {

        // --- 1. Validate and compute metrics for every variant ---------------------
        List<VariantIntermediate> intermediates = new ArrayList<>(req.variants().size());
        for (DesignVariant v : req.variants()) {
            intermediates.add(processVariant(v));
        }

        // --- 2. Render previews at a unified scale --------------------------------
        // Use the smallest pixels-per-mm value so that every preview is at the
        // same physical scale (the most "coarse" design sets the common scale).
        double minPixelsPerMm = intermediates.stream()
                .mapToDouble(i -> i.pixelsPerMm)
                .min()
                .orElseThrow();

        List<DesignComparisonResult.VariantResult> results = new ArrayList<>(intermediates.size());
        for (VariantIntermediate im : intermediates) {
            results.add(buildVariantResult(im, minPixelsPerMm, null));
        }

        // --- 3. Compute parameter differences ------------------------------------
        List<ParameterDifference> diffs = computeParameterDifferences(req.variants());

        // --- 4. Optional ranking --------------------------------------------------
        List<DesignComparisonResult.VariantResult> finalResults = results;
        if (req.rank()) {
            List<RankedEntry> ranked = rank(intermediates);
            List<DesignComparisonResult.VariantResult> rankedResults = new ArrayList<>(results.size());
            for (int i = 0; i < results.size(); i++) {
                final int idx = i;
                VariantScore score = ranked.stream()
                        .filter(r -> r.index == idx)
                        .findFirst()
                        .map(r -> new VariantScore(r.rank, r.score, r.explanation))
                        .orElse(null);
                DesignComparisonResult.VariantResult old = results.get(i);
                rankedResults.add(new DesignComparisonResult.VariantResult(
                        old.label(), old.pluginId(), old.validation(),
                        old.previewBase64(), old.previewWidthPx(), old.previewHeightPx(),
                        old.pixelsPerMm(), score));
            }
            finalResults = rankedResults;
        }

        return new DesignComparisonResult(finalResults, diffs);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private VariantIntermediate processVariant(DesignVariant v) throws IOException {
        return switch (v.pluginId()) {
            case DesignVariant.PLUGIN_SINGLE -> {
                if (v.singleParams() == null)
                    throw new IllegalArgumentException(
                            "Variant \"" + v.label() + "\": singleParams must be set for pluginId=\"single\"");
                SingleZonePlateParameters p = v.singleParams().toParameters();
                ValidationResult vr = DesignValidator.validate(p);
                RenderResult rr = ZonePlateRenderer.render(p);
                yield new VariantIntermediate(v, p, vr, rr, p.dpi() / 25.4);
            }
            case DesignVariant.PLUGIN_RGB -> {
                if (v.rgbParams() == null)
                    throw new IllegalArgumentException(
                            "Variant \"" + v.label() + "\": rgbParams must be set for pluginId=\"rgb\"");
                SingleZonePlateParameters base = v.rgbParams().base().toParameters();
                ValidationResult vr = DesignValidator.validate(base);
                RenderResult rr = RgbZonePlateRenderer.render(base,
                        v.rgbParams().redNm(), v.rgbParams().greenNm(), v.rgbParams().blueNm());
                yield new VariantIntermediate(v, base, vr, rr, base.dpi() / 25.4);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown pluginId \"" + v.pluginId() + "\"; supported: single, rgb");
        };
    }

    private DesignComparisonResult.VariantResult buildVariantResult(
            VariantIntermediate im, double targetPixelsPerMm, VariantScore score) throws IOException {

        // Re-scale the rendered image so that every preview shares the same
        // physical scale (targetPixelsPerMm).
        BufferedImage src = im.renderResult.image();
        int newSide = (int) Math.round(
                src.getWidth() * targetPixelsPerMm / im.pixelsPerMm);
        // Clamp to the maximum preview size.
        newSide = Math.min(newSide, MAX_COMPARISON_PREVIEW_PX);
        newSide = Math.max(newSide, 1);

        BufferedImage scaled = scaleImage(src, newSide, newSide);

        // Encode to PNG and then base-64.
        RenderResult scaledResult = new RenderResult(scaled, im.renderResult.pixelSizeMm());
        byte[] pngBytes = PngExporter.toPngBytes(scaledResult, im.singleParams.dpi());
        String b64 = Base64.getEncoder().encodeToString(pngBytes);

        return new DesignComparisonResult.VariantResult(
                im.variant.label(),
                im.variant.pluginId(),
                ValidationResponse.from(im.validation),
                b64,
                scaled.getWidth(),
                scaled.getHeight(),
                targetPixelsPerMm,
                score);
    }

    /** Bilinear (or nearest-neighbour via Graphics2D) downscale. */
    private static BufferedImage scaleImage(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        BufferedImage dst = new BufferedImage(w, h,
                src.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : src.getType());
        java.awt.Graphics2D g = dst.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    // -------------------------------------------------------------------------
    // Parameter-difference extraction
    // -------------------------------------------------------------------------

    private List<ParameterDifference> computeParameterDifferences(List<DesignVariant> variants) {
        // Build a map of parameter → list of formatted values (one per variant).
        // We compare every shared parameter; parameters absent in a variant are
        // represented as "-".
        Map<String, String[]> table = new LinkedHashMap<>();
        int n = variants.size();

        for (int i = 0; i < n; i++) {
            DesignVariant v = variants.get(i);
            Map<String, String> params = extractParameters(v);
            for (Map.Entry<String, String> e : params.entrySet()) {
                table.computeIfAbsent(e.getKey(), k -> new String[n])[i] = e.getValue();
            }
        }

        // Fill missing entries and keep only rows where not all values are equal.
        List<ParameterDifference> diffs = new ArrayList<>();
        for (Map.Entry<String, String[]> e : table.entrySet()) {
            String[] vals = e.getValue();
            for (int i = 0; i < n; i++) {
                if (vals[i] == null) vals[i] = "-";
            }
            if (!allEqual(vals)) {
                diffs.add(new ParameterDifference(
                        e.getKey(),
                        unitFor(e.getKey()),
                        List.of(vals)));
            }
        }
        diffs.sort(Comparator.comparing(ParameterDifference::parameter));
        return diffs;
    }

    private Map<String, String> extractParameters(DesignVariant v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pluginId", v.pluginId());
        if (v.singleParams() != null) {
            SingleZonePlateRequest p = v.singleParams();
            m.put("apertureDiameterMm", fmt(p.apertureDiameterMm()));
            m.put("focalLengthMm",      fmt(p.focalLengthMm()));
            m.put("wavelengthNm",       fmt(p.wavelengthNm()));
            m.put("dpi",                fmt(p.dpi()));
            m.put("maskType",           orDefault(p.maskType(), "BINARY_AMPLITUDE"));
            m.put("polarity",           orDefault(p.polarity(), "POSITIVE"));
            m.put("targetOffsetXmm",    fmt(p.targetOffsetXmm() == null ? 0.0 : p.targetOffsetXmm()));
            m.put("targetOffsetYmm",    fmt(p.targetOffsetYmm() == null ? 0.0 : p.targetOffsetYmm()));
        }
        if (v.rgbParams() != null) {
            m.put("redNm",   fmt(v.rgbParams().redNm()));
            m.put("greenNm", fmt(v.rgbParams().greenNm()));
            m.put("blueNm",  fmt(v.rgbParams().blueNm()));
        }
        return m;
    }

    private static String fmt(Double d) {
        if (d == null) return "-";
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String orDefault(Object val, String def) {
        return val == null ? def : val.toString();
    }

    private static String unitFor(String param) {
        return switch (param) {
            case "apertureDiameterMm", "focalLengthMm",
                 "targetOffsetXmm", "targetOffsetYmm" -> "mm";
            case "wavelengthNm", "redNm", "greenNm", "blueNm" -> "nm";
            case "dpi" -> "dpi";
            default -> "";
        };
    }

    private static boolean allEqual(String[] vals) {
        for (int i = 1; i < vals.length; i++) {
            if (!vals[0].equals(vals[i])) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Ranking
    // -------------------------------------------------------------------------

    /**
     * Rank variants by a composite score:
     * <ul>
     *   <li>40 % printability  – pixels per outer zone, normalized</li>
     *   <li>30 % resolution    – numerical aperture, normalized</li>
     *   <li>30 % focus depth   – reciprocal depth-of-focus (smaller DoF is better for tight focus),
     *       normalized</li>
     * </ul>
     * The ranking is deterministic: equal scores preserve submission order.
     */
    private List<RankedEntry> rank(List<VariantIntermediate> intermediates) {
        int n = intermediates.size();

        // Collect raw scores.
        double[] pixPerZone = new double[n];
        double[] na         = new double[n];
        double[] dofRecip   = new double[n];

        for (int i = 0; i < n; i++) {
            VariantIntermediate im = intermediates.get(i);
            pixPerZone[i] = im.validation.metrics().pixelsPerOuterZone();
            OpticalQualityReport qr = im.validation.qualityReport();
            na[i]       = (qr != null) ? qr.numericalAperture()    : 0.0;
            dofRecip[i] = (qr != null && qr.depthOfFocusMicrons() > 0)
                    ? 1.0 / qr.depthOfFocusMicrons() : 0.0;
        }

        double maxPix  = max(pixPerZone);
        double maxNA   = max(na);
        double maxRecip = max(dofRecip);

        double[] composite = new double[n];
        for (int i = 0; i < n; i++) {
            double pPrint = maxPix   > 0 ? pixPerZone[i] / maxPix   : 0.0;
            double pNA    = maxNA    > 0 ? na[i]         / maxNA    : 0.0;
            double pDoF   = maxRecip > 0 ? dofRecip[i]   / maxRecip : 0.0;
            composite[i]  = 0.40 * pPrint + 0.30 * pNA + 0.30 * pDoF;
        }

        // Sort descending by composite score; ties: lower index wins.
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            int cmp = Double.compare(composite[b], composite[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });

        List<RankedEntry> result = new ArrayList<>(n);
        for (int rank = 1; rank <= n; rank++) {
            int i = idx[rank - 1];
            String expl = String.format(
                    "Composite score %.3f — printability %.3f (px/zone=%.2f), "
                    + "NA %.3f, 1/DoF %.3e",
                    composite[i],
                    0.40 * (maxPix > 0 ? pixPerZone[i] / maxPix : 0),
                    pixPerZone[i],
                    na[i],
                    dofRecip[i]);
            result.add(new RankedEntry(i, rank, composite[i], expl));
        }
        return result;
    }

    private static double max(double[] arr) {
        if (arr.length == 0) return 0.0;
        double m = arr[0];
        for (double v : arr) if (v > m) m = v;
        return m;
    }

    // -------------------------------------------------------------------------
    // Value objects
    // -------------------------------------------------------------------------

    private record VariantIntermediate(
            DesignVariant variant,
            SingleZonePlateParameters singleParams,
            ValidationResult validation,
            RenderResult renderResult,
            double pixelsPerMm
    ) {}

    private record RankedEntry(int index, int rank, double score, String explanation) {}
}
