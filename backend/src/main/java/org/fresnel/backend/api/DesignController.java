package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.optics.DesignValidator;
import org.fresnel.optics.MultiFocusRenderer;
import org.fresnel.optics.PdfExporter;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.fresnel.optics.RgbZonePlateRenderer;
import org.fresnel.optics.SingleZonePlateParameters;
import org.fresnel.optics.SvgExporter;
import org.fresnel.optics.ValidationResult;
import org.fresnel.optics.HexMacroCellRenderer;
import org.fresnel.optics.WindowFoilRenderer;
import org.fresnel.optics.HexMacroCellParameters;
import org.fresnel.optics.WindowFoilParameters;
import org.fresnel.optics.MultiFocusParameters;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Synchronous endpoints for designing and previewing Fresnel zone plates and related
 * diffractive elements.
 *
 * <p>Synchronous renders are capped at {@link #MAX_PREVIEW_PX} per side; for larger
 * outputs (window foils, very high-DPI macro cells, holograms) clients should use
 * the async render-job endpoints in {@code RenderJobController} which stream
 * progress over Server-Sent Events.
 */
@RestController
@RequestMapping("/api/designs")
public class DesignController {

    /** Maximum image side (in pixels) allowed for synchronous PNG preview. */
    public static final long MAX_PREVIEW_PX = 4096;

    // -------- Single zone plate (Use Case A) --------

    @PostMapping(value = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationResponse validate(@Valid @RequestBody SingleZonePlateRequest req) {
        SingleZonePlateParameters params = req.toParameters();
        ValidationResult v = DesignValidator.validate(params);
        return ValidationResponse.from(v);
    }

    @PostMapping(value = "/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewPng(@Valid @RequestBody SingleZonePlateRequest req) throws IOException {
        SingleZonePlateParameters params = req.toParameters();
        long sizePx = estimateSizePx(params);
        if (sizePx > MAX_PREVIEW_PX) {
            return tooLarge(sizePx);
        }
        return renderSinglePng(params, "inline", "fresnel-zone-plate.png");
    }

    @PostMapping(value = "/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> exportPng(@Valid @RequestBody SingleZonePlateRequest req) throws IOException {
        return renderSinglePng(req.toParameters(), "attachment", "fresnel-zone-plate.png");
    }

    @PostMapping(value = "/export.svg",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "image/svg+xml")
    public ResponseEntity<byte[]> exportSvg(@Valid @RequestBody SingleZonePlateRequest req,
                                            @RequestParam(value = "vector", defaultValue = "true") boolean vector)
            throws IOException {
        SingleZonePlateParameters params = req.toParameters();
        boolean canVector = vector
                && (params.targetOffsetXmm() == 0.0)
                && (params.targetOffsetYmm() == 0.0)
                && params.maskType() == org.fresnel.optics.MaskType.BINARY_AMPLITUDE;
        byte[] svg = canVector
                ? SvgExporter.toSvgZonePlateBytes(params)
                : SvgExporter.toSvgRasterBytes(
                        org.fresnel.optics.ZonePlateRenderer.render(params), params.dpi());
        return svgResponse(svg, "fresnel-zone-plate.svg");
    }

    @PostMapping(value = "/export.pdf",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@Valid @RequestBody SingleZonePlateRequest req,
                                            @RequestParam(value = "sheet", defaultValue = "FIT") String sheet)
            throws IOException {
        PdfExporter.SheetSize size = parseSheetSize(sheet);
        RenderResult r = org.fresnel.optics.ZonePlateRenderer.render(req.toParameters());
        byte[] pdf = PdfExporter.toPdfBytes(r, size);
        return pdfResponse(pdf, "fresnel-zone-plate.pdf");
    }

    @PostMapping(value = "/export.dxf",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/dxf")
    public ResponseEntity<byte[]> exportDxf(@Valid @RequestBody SingleZonePlateRequest req) throws IOException {
        byte[] dxf = org.fresnel.optics.DxfExporter.toDxfBytes(req.toParameters());
        return vendorResponse(dxf, "application/dxf", "fresnel-zone-plate.dxf");
    }

    @PostMapping(value = "/export.gbr",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/vnd.gerber")
    public ResponseEntity<byte[]> exportGerber(@Valid @RequestBody SingleZonePlateRequest req) throws IOException {
        byte[] gbr = org.fresnel.optics.GerberExporter.toGerberBytes(req.toParameters());
        return vendorResponse(gbr, "application/vnd.gerber", "fresnel-zone-plate.gbr");
    }

    // -------- Hex macro cell (Use Case B) --------

    @PostMapping(value = "/hex/info",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> hexInfo(@Valid @RequestBody HexMacroCellRequest req) {
        HexMacroCellParameters p = req.toParameters();
        int n = HexMacroCellRenderer.countSubElements(p);
        long sidePx = (long) Math.ceil(2.0 * p.macroRadiusMm() / (25.4 / p.dpi()));
        return Map.of("subElements", n, "imageSidePx", sidePx);
    }

    @PostMapping(value = "/hex/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> hexPreview(@Valid @RequestBody HexMacroCellRequest req) throws IOException {
        HexMacroCellParameters p = req.toParameters();
        long sizePx = (long) Math.ceil(2.0 * p.macroRadiusMm() / (25.4 / p.dpi()));
        if (sizePx > MAX_PREVIEW_PX) return tooLarge(sizePx);
        RenderResult r = HexMacroCellRenderer.render(p);
        return pngResponse(PngExporter.toPngBytes(r, p.dpi()), "inline", "fresnel-hex-macro.png");
    }

    @PostMapping(value = "/hex/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> hexExportPng(@Valid @RequestBody HexMacroCellRequest req) throws IOException {
        RenderResult r = HexMacroCellRenderer.render(req.toParameters());
        return pngResponse(PngExporter.toPngBytes(r, req.dpi()), "attachment", "fresnel-hex-macro.png");
    }

    @PostMapping(value = "/hex/export.svg",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "image/svg+xml")
    public ResponseEntity<byte[]> hexExportSvg(@Valid @RequestBody HexMacroCellRequest req) throws IOException {
        RenderResult r = HexMacroCellRenderer.render(req.toParameters());
        return svgResponse(SvgExporter.toSvgRasterBytes(r, req.dpi()), "fresnel-hex-macro.svg");
    }

    @PostMapping(value = "/hex/export.pdf",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> hexExportPdf(@Valid @RequestBody HexMacroCellRequest req,
                                               @RequestParam(value = "sheet", defaultValue = "FIT") String sheet)
            throws IOException {
        RenderResult r = HexMacroCellRenderer.render(req.toParameters());
        return pdfResponse(PdfExporter.toPdfBytes(r, parseSheetSize(sheet)), "fresnel-hex-macro.pdf");
    }

    // -------- Window foil (Use Case C) --------

    @PostMapping(value = "/foil/info",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> foilInfo(@Valid @RequestBody WindowFoilRequest req) {
        WindowFoilParameters p = req.toParameters();
        int n = WindowFoilRenderer.countCells(p);
        long wPx = (long) Math.ceil(p.sheetWidthMm() / (25.4 / p.dpi()));
        long hPx = (long) Math.ceil(p.sheetHeightMm() / (25.4 / p.dpi()));
        return Map.of("cells", n, "imageWidthPx", wPx, "imageHeightPx", hPx);
    }

    @PostMapping(value = "/foil/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> foilPreview(@Valid @RequestBody WindowFoilRequest req) throws IOException {
        WindowFoilParameters p = req.toParameters();
        long wPx = (long) Math.ceil(p.sheetWidthMm() / (25.4 / p.dpi()));
        long hPx = (long) Math.ceil(p.sheetHeightMm() / (25.4 / p.dpi()));
        if (wPx > MAX_PREVIEW_PX || hPx > MAX_PREVIEW_PX) return tooLarge(Math.max(wPx, hPx));
        RenderResult r = WindowFoilRenderer.render(p);
        return pngResponse(PngExporter.toPngBytes(r, p.dpi()), "inline", "fresnel-window-foil.png");
    }

    @PostMapping(value = "/foil/export.pdf",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> foilExportPdf(@Valid @RequestBody WindowFoilRequest req,
                                                @RequestParam(value = "sheet", defaultValue = "A4") String sheet)
            throws IOException {
        RenderResult r = WindowFoilRenderer.render(req.toParameters());
        return pdfResponse(PdfExporter.toPdfBytes(r, parseSheetSize(sheet)), "fresnel-window-foil.pdf");
    }

    // -------- Multi-focus (Mode 4) --------

    @PostMapping(value = "/multifocus/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> multiFocusPreview(@Valid @RequestBody MultiFocusRequest req) throws IOException {
        MultiFocusParameters p = req.toParameters();
        long sizePx = (long) Math.ceil(p.apertureDiameterMm() / (25.4 / p.dpi()));
        if (sizePx > MAX_PREVIEW_PX) return tooLarge(sizePx);
        RenderResult r = MultiFocusRenderer.render(p);
        return pngResponse(PngExporter.toPngBytes(r, p.dpi()), "inline", "fresnel-multifocus.png");
    }

    @PostMapping(value = "/multifocus/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> multiFocusExport(@Valid @RequestBody MultiFocusRequest req) throws IOException {
        RenderResult r = MultiFocusRenderer.render(req.toParameters());
        return pngResponse(PngExporter.toPngBytes(r, req.dpi()), "attachment", "fresnel-multifocus.png");
    }

    // -------- RGB / multi-wavelength (Mode 5) --------

    @PostMapping(value = "/rgb/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> rgbPreview(@Valid @RequestBody RgbZonePlateRequest req) throws IOException {
        SingleZonePlateParameters base = req.base().toParameters();
        long sizePx = estimateSizePx(base);
        if (sizePx > MAX_PREVIEW_PX) return tooLarge(sizePx);
        RenderResult r = RgbZonePlateRenderer.render(base, req.redNm(), req.greenNm(), req.blueNm());
        return pngResponse(PngExporter.toPngBytes(r, base.dpi()), "inline", "fresnel-rgb.png");
    }

    @PostMapping(value = "/rgb/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> rgbExport(@Valid @RequestBody RgbZonePlateRequest req) throws IOException {
        SingleZonePlateParameters base = req.base().toParameters();
        RenderResult r = RgbZonePlateRenderer.render(base, req.redNm(), req.greenNm(), req.blueNm());
        return pngResponse(PngExporter.toPngBytes(r, base.dpi()), "attachment", "fresnel-rgb.png");
    }

    // -------- Helpers --------

    private static long estimateSizePx(SingleZonePlateParameters p) {
        double pixelMm = 25.4 / p.dpi();
        return Math.round(p.apertureDiameterMm() / pixelMm);
    }

    private static ResponseEntity<byte[]> renderSinglePng(SingleZonePlateParameters params,
                                                          String disposition, String filename) throws IOException {
        RenderResult r = org.fresnel.optics.ZonePlateRenderer.render(params);
        return pngResponse(PngExporter.toPngBytes(r, params.dpi()), disposition, filename);
    }

    private static ResponseEntity<byte[]> pngResponse(byte[] body, String disposition, String filename) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.IMAGE_PNG);
        h.setContentDispositionFormData(disposition, filename);
        return new ResponseEntity<>(body, h, 200);
    }

    private static ResponseEntity<byte[]> svgResponse(byte[] body, String filename) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("image/svg+xml"));
        h.setContentDispositionFormData("attachment", filename);
        return new ResponseEntity<>(body, h, 200);
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] body, String filename) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_PDF);
        h.setContentDispositionFormData("attachment", filename);
        return new ResponseEntity<>(body, h, 200);
    }

    private static ResponseEntity<byte[]> vendorResponse(byte[] body, String mime, String filename) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(mime));
        h.setContentDispositionFormData("attachment", filename);
        return new ResponseEntity<>(body, h, 200);
    }

    private static ResponseEntity<byte[]> tooLarge(long sizePx) {
        return ResponseEntity.status(413)
                .contentType(MediaType.TEXT_PLAIN)
                .body(("Image would be " + sizePx + " px wide; use async render jobs for > "
                        + MAX_PREVIEW_PX + " px.").getBytes());
    }

    private static PdfExporter.SheetSize parseSheetSize(String s) {
        try {
            return PdfExporter.SheetSize.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown sheet size: " + s
                    + " (allowed: A0,A1,A2,A3,A4,FIT)");
        }
    }
}
