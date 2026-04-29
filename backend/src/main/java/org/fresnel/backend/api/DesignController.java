package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.optics.DesignValidator;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.fresnel.optics.SingleZonePlateParameters;
import org.fresnel.optics.ValidationResult;
import org.fresnel.optics.ZonePlateRenderer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Synchronous endpoints for designing and previewing single Fresnel zone plates.
 */
@RestController
@RequestMapping("/api/designs")
public class DesignController {

    /** Maximum image side (in pixels) allowed for synchronous PNG preview. */
    public static final long MAX_PREVIEW_PX = 4096;

    /**
     * Validate a design and return printability metrics + warnings.
     */
    @PostMapping(value = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationResponse validate(@Valid @RequestBody SingleZonePlateRequest req) {
        SingleZonePlateParameters params = req.toParameters();
        ValidationResult v = DesignValidator.validate(params);
        return ValidationResponse.from(v);
    }

    /**
     * Render a PNG preview of the design and return it as image/png.
     * <p>
     * Capped at {@link #MAX_PREVIEW_PX} so synchronous preview stays fast; very large
     * designs should use the (planned) async render-jobs endpoint.
     */
    @PostMapping(value = "/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewPng(@Valid @RequestBody SingleZonePlateRequest req) throws IOException {
        SingleZonePlateParameters params = req.toParameters();
        long sizePx = estimateSizePx(params);
        if (sizePx > MAX_PREVIEW_PX) {
            return ResponseEntity.status(413)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Image would be " + sizePx + " px wide; use async render jobs for > "
                            + MAX_PREVIEW_PX + " px.").getBytes());
        }
        return renderPng(params, "inline", "fresnel-zone-plate.png");
    }

    /** Render and return a PNG suitable for download (no preview cap). */
    @PostMapping(value = "/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> exportPng(@Valid @RequestBody SingleZonePlateRequest req) throws IOException {
        return renderPng(req.toParameters(), "attachment", "fresnel-zone-plate.png");
    }

    private static long estimateSizePx(SingleZonePlateParameters p) {
        double pixelMm = 25.4 / p.dpi();
        return Math.round(p.apertureDiameterMm() / pixelMm);
    }

    private static ResponseEntity<byte[]> renderPng(SingleZonePlateParameters params,
                                                    String disposition, String filename) throws IOException {
        RenderResult r = ZonePlateRenderer.render(params);
        byte[] png = PngExporter.toPngBytes(r, params.dpi());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentDispositionFormData(disposition, filename);
        return new ResponseEntity<>(png, headers, 200);
    }
}
