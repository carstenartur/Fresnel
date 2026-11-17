package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.backend.measurement.BosTargetPngEncoder;
import org.fresnel.backend.measurement.BosTargetPrintExporter;
import org.fresnel.measurement.BosTarget;
import org.fresnel.measurement.BosTargetGenerator;
import org.fresnel.optics.PdfExporter.SheetSize;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Public, deterministic target-generation endpoints for the BOS measurement plugin. */
@RestController
@RequestMapping("/api/measurements/background-oriented-schlieren")
public class BosTargetController {

    private static final int THUMBNAIL_SIDE_PX = 1600;

    public static final String TARGET_SHA256_HEADER = "X-Fresnel-Target-SHA256";
    public static final String TARGET_ID_HEADER = "X-Fresnel-Target-Id";
    public static final String ACTIVE_REGION_HEADER = "X-Fresnel-Active-Region";

    @PostMapping(
            value = "/target/manifest",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public BosTarget.Manifest manifest(@Valid @RequestBody BosTargetRequest request) {
        return generate(request).manifest();
    }

    @PostMapping(
            value = "/target/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> preview(
            @Valid @RequestBody BosTargetRequest request,
            @RequestParam(value = "thumbnail", defaultValue = "false") boolean thumbnail) {
        BosTarget target = generate(request);
        return imageResponse(target, false, thumbnail);
    }

    @PostMapping(
            value = "/target/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> export(@Valid @RequestBody BosTargetRequest request) {
        BosTarget target = generate(request);
        return imageResponse(target, true, false);
    }

    @PostMapping(value = "/target/export.svg", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "image/svg+xml")
    public ResponseEntity<byte[]> exportSvg(@Valid @RequestBody BosTargetRequest request,
            @RequestParam(value = "calibrationStrip", defaultValue = "true") boolean strip) {
        BosTarget target = generate(request);
        return output(target, BosTargetPrintExporter.svg(target, strip), MediaType.valueOf("image/svg+xml"),
                ".svg", true, "-svg-" + strip);
    }

    @PostMapping(value = "/target/export.pdf", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@Valid @RequestBody BosTargetRequest request,
            @RequestParam(value = "calibrationStrip", defaultValue = "true") boolean strip,
            @RequestParam(value = "sheet", defaultValue = "FIT") SheetSize sheet) throws IOException {
        BosTarget target = generate(request);
        return output(target, BosTargetPrintExporter.pdf(target, strip, sheet), MediaType.APPLICATION_PDF,
                ".pdf", true, "-pdf-" + strip + "-" + sheet);
    }

    private static BosTarget generate(BosTargetRequest request) {
        return BosTargetGenerator.generate(request.toParameters());
    }

    private static ResponseEntity<byte[]> imageResponse(
            BosTarget target, boolean attachment, boolean thumbnail) {
        byte[] png = thumbnail
                ? BosTargetPngEncoder.thumbnail(target, THUMBNAIL_SIDE_PX)
                : BosTargetPngEncoder.encode(target);
        return output(target, png, MediaType.IMAGE_PNG, ".png", attachment, thumbnail ? "-thumbnail" : "");
    }

    private static ResponseEntity<byte[]> output(BosTarget target, byte[] bytes, MediaType type,
            String extension, boolean attachment, String variant) {
        BosTarget.Region active = target.activeRegion();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag('"' + target.semanticSha256() + variant + '"');
        headers.set(TARGET_SHA256_HEADER, target.semanticSha256());
        headers.set(TARGET_ID_HEADER, target.targetId());
        headers.set("X-Fresnel-Pattern-Seed", Long.toString(target.parameters().patternSeed()));
        headers.set("X-Fresnel-Dot-Count", Integer.toString(target.dotCount()));
        headers.set("X-Fresnel-Achieved-Fill-Ratio", Double.toString(target.actualFillRatio()));
        headers.set(ACTIVE_REGION_HEADER,
                active.x() + "," + active.y() + "," + active.width() + "," + active.height());
        headers.add("X-Content-Type-Options", "nosniff");
        ContentDisposition disposition = attachment
                ? ContentDisposition.attachment().filename(target.targetId() + extension).build()
                : ContentDisposition.inline().filename(target.targetId() + extension).build();
        headers.setContentDisposition(disposition);
        return ResponseEntity.ok().headers(headers).contentLength(bytes.length).body(bytes);
    }
}
