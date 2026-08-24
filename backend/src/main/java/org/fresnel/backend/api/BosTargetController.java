package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.backend.measurement.BosTargetPngEncoder;
import org.fresnel.measurement.BosTarget;
import org.fresnel.measurement.BosTargetGenerator;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, deterministic target-generation endpoints for the BOS measurement plugin. */
@RestController
@RequestMapping("/api/measurements/background-oriented-schlieren")
public class BosTargetController {

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
    public ResponseEntity<byte[]> preview(@Valid @RequestBody BosTargetRequest request) {
        BosTarget target = generate(request);
        return imageResponse(target, false);
    }

    @PostMapping(
            value = "/target/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> export(@Valid @RequestBody BosTargetRequest request) {
        BosTarget target = generate(request);
        return imageResponse(target, true);
    }

    private static BosTarget generate(BosTargetRequest request) {
        return BosTargetGenerator.generate(request.toParameters());
    }

    private static ResponseEntity<byte[]> imageResponse(BosTarget target, boolean attachment) {
        byte[] png = BosTargetPngEncoder.encode(target);
        BosTarget.Region active = target.activeRegion();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag('"' + target.semanticSha256() + '"');
        headers.set(TARGET_SHA256_HEADER, target.semanticSha256());
        headers.set(TARGET_ID_HEADER, target.targetId());
        headers.set(ACTIVE_REGION_HEADER,
                active.x() + "," + active.y() + "," + active.width() + "," + active.height());
        headers.add("X-Content-Type-Options", "nosniff");
        ContentDisposition disposition = attachment
                ? ContentDisposition.attachment().filename(target.targetId() + ".png").build()
                : ContentDisposition.inline().filename(target.targetId() + ".png").build();
        headers.setContentDisposition(disposition);
        return ResponseEntity.ok().headers(headers).contentLength(png.length).body(png);
    }
}
