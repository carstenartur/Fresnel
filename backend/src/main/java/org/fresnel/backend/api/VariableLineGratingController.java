package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.optics.PclCompression;
import org.fresnel.optics.PclExporter;
import org.fresnel.optics.PdfExporter;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.PrinterRasterProfile;
import org.fresnel.optics.PrinterRasterProfiles;
import org.fresnel.optics.RenderResult;
import org.fresnel.optics.VariableLineGratingAnalysis;
import org.fresnel.optics.VariableLineGratingParameters;
import org.fresnel.optics.VariableLineGratingRenderer;
import org.fresnel.optics.VariableLineGratingSvgExporter;
import org.fresnel.optics.VariableLineGratingValidation;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** HTTP endpoints for the orientation-selectable variable-line grating plugin. */
@RestController
@RequestMapping("/api/designs/variable-line-grating")
public class VariableLineGratingController {

    private static final int MAX_PREVIEW_SIDE_PX = 4096;

    @GetMapping(value = "/printer-profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PrinterRasterProfile> printerProfiles() {
        return PrinterRasterProfiles.ALL;
    }

    @PostMapping(
            value = "/info",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public VariableLineGratingAnalysis.Result info(
            @Valid @RequestBody VariableLineGratingRequest request,
            @RequestParam(value = "printerProfileId", required = false) String printerProfileId) {
        PrinterRasterProfile profile = printerProfileId == null
                ? null
                : PrinterRasterProfiles.require(printerProfileId);
        return VariableLineGratingAnalysis.analyze(request.toParameters(), profile);
    }

    @PostMapping(
            value = "/validation",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public org.fresnel.optics.DesignValidationReport validation(
            @Valid @RequestBody VariableLineGratingRequest request,
            @RequestParam(value = "printerProfileId", required = false) String printerProfileId) {
        PrinterRasterProfile profile = printerProfileId == null
                ? null
                : PrinterRasterProfiles.require(printerProfileId);
        return VariableLineGratingValidation.report(request.toParameters(), profile);
    }

    @PostMapping(
            value = "/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> preview(@Valid @RequestBody VariableLineGratingRequest request)
            throws IOException {
        VariableLineGratingParameters preview = previewParameters(request.toParameters());
        RenderResult rendered = VariableLineGratingRenderer.render(preview);
        return response(
                PngExporter.toPngBytes(rendered, preview.dpi()),
                MediaType.IMAGE_PNG,
                "inline",
                "fresnel-variable-line-grating-preview.png");
    }

    @PostMapping(
            value = "/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> exportPng(@Valid @RequestBody VariableLineGratingRequest request)
            throws IOException {
        VariableLineGratingParameters parameters = request.toParameters();
        RenderResult rendered = VariableLineGratingRenderer.render(parameters);
        return response(
                PngExporter.toPngBytes(rendered, parameters.dpi()),
                MediaType.IMAGE_PNG,
                "attachment",
                "fresnel-variable-line-grating.png");
    }

    @PostMapping(
            value = "/export.svg",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "image/svg+xml")
    public ResponseEntity<byte[]> exportSvg(@Valid @RequestBody VariableLineGratingRequest request) {
        return response(
                VariableLineGratingSvgExporter.toSvgBytes(request.toParameters()),
                MediaType.parseMediaType("image/svg+xml"),
                "attachment",
                "fresnel-variable-line-grating.svg");
    }

    @PostMapping(
            value = "/export.pdf",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @Valid @RequestBody VariableLineGratingRequest request,
            @RequestParam(value = "sheet", defaultValue = "A4") String sheet)
            throws IOException {
        RenderResult rendered = VariableLineGratingRenderer.render(request.toParameters());
        return response(
                PdfExporter.toPdfBytes(rendered, parseSheetSize(sheet)),
                MediaType.APPLICATION_PDF,
                "attachment",
                "fresnel-variable-line-grating.pdf");
    }

    @PostMapping(
            value = "/export.pcl",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = PclExporter.MEDIA_TYPE)
    public ResponseEntity<byte[]> exportPcl(
            @Valid @RequestBody VariableLineGratingRequest request,
            @RequestParam(value = "printerProfileId", defaultValue = PrinterRasterProfiles.DEFAULT_PROFILE_ID)
            String printerProfileId,
            @RequestParam(value = "compression", defaultValue = "TIFF") PclCompression compression) {
        PrinterRasterProfile profile = PrinterRasterProfiles.require(printerProfileId);
        byte[] pcl = PclExporter.toPclBytes(request.toParameters(), profile, compression);
        return response(
                pcl,
                MediaType.parseMediaType(PclExporter.MEDIA_TYPE),
                "attachment",
                "fresnel-variable-line-grating.pcl");
    }

    private static VariableLineGratingParameters previewParameters(VariableLineGratingParameters p) {
        double maxDimensionMm = Math.max(p.widthMm(), p.heightMm());
        double maxPreviewDpi = MAX_PREVIEW_SIDE_PX * 25.4 / maxDimensionMm;
        double dpi = Math.min(p.dpi(), maxPreviewDpi);
        if (Double.compare(dpi, p.dpi()) == 0) return p;
        return new VariableLineGratingParameters(
                p.widthMm(),
                p.heightMm(),
                p.lineOrientation(),
                p.startPitchUm(),
                p.endPitchUm(),
                p.progression(),
                p.progressionDirection(),
                p.dutyCycle(),
                p.phaseOffsetCycles(),
                p.polarity(),
                p.marginMm(),
                p.annotationSizeMm(),
                p.showAxis(),
                p.axisQuantity(),
                p.tickCount(),
                p.showReferenceBands(),
                p.referenceBandSizeMm(),
                dpi);
    }

    private static PdfExporter.SheetSize parseSheetSize(String value) {
        try {
            return PdfExporter.SheetSize.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "unknown sheet size: " + value + " (allowed: A0,A1,A2,A3,A4,FIT)",
                    exception);
        }
    }

    private static ResponseEntity<byte[]> response(
            byte[] body,
            MediaType mediaType,
            String disposition,
            String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition("inline".equals(disposition)
                ? ContentDisposition.inline().filename(filename).build()
                : ContentDisposition.attachment().filename(filename).build());
        headers.add("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
