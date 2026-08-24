package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.optics.BackgroundOrientedSchlierenParameters;
import org.fresnel.optics.BackgroundOrientedSchlierenRenderer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Deterministic capture-target endpoints for background-oriented schlieren. */
@RestController
@RequestMapping("/api/designs/background-oriented-schlieren")
public class BackgroundOrientedSchlierenController {

    private static final int MAX_PREVIEW_SIDE_PX = 1600;

    @PostMapping(
            value = "/preview.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> preview(
            @Valid @RequestBody BackgroundOrientedSchlierenRequest request)
            throws IOException {
        BackgroundOrientedSchlierenParameters parameters = request.toParameters();
        BackgroundOrientedSchlierenRenderer.Result rendered =
                BackgroundOrientedSchlierenRenderer.render(parameters);
        return response(
                png(scaleForPreview(rendered.image())),
                "inline",
                "fresnel-background-oriented-schlieren-preview.png",
                parameters,
                rendered);
    }

    @PostMapping(
            value = "/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> export(
            @Valid @RequestBody BackgroundOrientedSchlierenRequest request)
            throws IOException {
        BackgroundOrientedSchlierenParameters parameters = request.toParameters();
        BackgroundOrientedSchlierenRenderer.Result rendered =
                BackgroundOrientedSchlierenRenderer.render(parameters);
        return response(
                png(rendered.image()),
                "attachment",
                "fresnel-background-oriented-schlieren.png",
                parameters,
                rendered);
    }

    private static BufferedImage scaleForPreview(BufferedImage source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_PREVIEW_SIDE_PX) return source;
        double factor = MAX_PREVIEW_SIDE_PX / (double) longest;
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage preview = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = preview.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return preview;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            return output.toByteArray();
        }
    }

    private static ResponseEntity<byte[]> response(
            byte[] body,
            String disposition,
            String filename,
            BackgroundOrientedSchlierenParameters parameters,
            BackgroundOrientedSchlierenRenderer.Result rendered) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentDisposition("inline".equals(disposition)
                ? ContentDisposition.inline().filename(filename).build()
                : ContentDisposition.attachment().filename(filename).build());
        headers.add("X-Content-Type-Options", "nosniff");
        headers.add("X-Fresnel-Pattern-Seed", Long.toString(parameters.patternSeed()));
        headers.add("X-Fresnel-Dot-Count", Integer.toString(rendered.dotCount()));
        headers.add(
                "X-Fresnel-Achieved-Fill-Ratio",
                String.format(Locale.ROOT, "%.8f", rendered.achievedFillRatio()));
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
