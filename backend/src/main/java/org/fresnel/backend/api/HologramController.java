package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.optics.HologramParameters;
import org.fresnel.optics.HologramSynthesizer;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Endpoints for Use Case D — hologram synthesis from a target image.
 *
 * <p>The target image is supplied as a base64-encoded PNG/JPEG in the JSON request
 * body, decoded server-side to a square greyscale power-of-two image of the
 * requested side length, then fed to the Gerchberg–Saxton synthesiser.
 */
@RestController
@RequestMapping("/api/holograms")
public class HologramController {

    /** Max side length for synchronous synthesis (1024 = ~1 M FFTs per iteration). */
    public static final int MAX_SIDE = 1024;

    @PostMapping(value = "/synthesize.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> synthesize(@Valid @RequestBody HologramRequest req) throws IOException {
        HologramParameters p = decode(req);
        RenderResult r = HologramSynthesizer.synthesize(p);
        byte[] png = PngExporter.toPngBytes(r, p.dpi());
        return png(png, "fresnel-hologram.png", "attachment");
    }

    @PostMapping(value = "/reconstruct.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> reconstruct(@Valid @RequestBody HologramRequest req,
                                              @RequestParam(value = "previewOnly", defaultValue = "false")
                                              boolean previewOnly) throws IOException {
        HologramParameters p = decode(req);
        RenderResult mask = HologramSynthesizer.synthesize(p);
        BufferedImage recon = HologramSynthesizer.reconstruct(mask.image(), p.outputType());
        BufferedImage out = previewOnly ? recon : sideBySide(mask.image(), recon);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(out, "png", baos);
        return png(baos.toByteArray(), "fresnel-hologram-reconstruction.png", "inline");
    }

    static HologramParameters decode(HologramRequest req) throws IOException {
        if (req.sidePx() > MAX_SIDE)
            throw new IllegalArgumentException("sidePx > " + MAX_SIDE + " requires async render-job");
        if ((req.sidePx() & (req.sidePx() - 1)) != 0)
            throw new IllegalArgumentException("sidePx must be a power of two");
        byte[] raw = Base64.getDecoder().decode(stripDataUrlPrefix(req.targetImageBase64()));
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) throw new IllegalArgumentException("could not decode targetImageBase64 as image");
        BufferedImage normalised = toSquareGreyscale(src, req.sidePx());
        HologramParameters.OutputType type = req.outputType() == null
                ? HologramParameters.OutputType.GREYSCALE_PHASE
                : req.outputType();
        return new HologramParameters(normalised, req.iterations(), type, req.dpi());
    }

    private static String stripDataUrlPrefix(String s) {
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) return s.substring(comma + 1);
        return s;
    }

    /** Centre-fit then resize to {@code n × n} greyscale. */
    static BufferedImage toSquareGreyscale(BufferedImage src, int n) {
        BufferedImage square = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = square.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, n, n);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int sw = src.getWidth();
            int sh = src.getHeight();
            int s = Math.min(sw, sh);
            int sx = (sw - s) / 2;
            int sy = (sh - s) / 2;
            g.drawImage(src, 0, 0, n, n, sx, sy, sx + s, sy + s, null);
        } finally {
            g.dispose();
        }
        return square;
    }

    private static BufferedImage sideBySide(BufferedImage a, BufferedImage b) {
        int w = a.getWidth() + b.getWidth();
        int h = Math.max(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(a, 0, 0, null);
            g.drawImage(b, a.getWidth(), 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static ResponseEntity<byte[]> png(byte[] body, String filename, String disposition) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.IMAGE_PNG);
        h.setContentDispositionFormData(disposition, filename);
        return new ResponseEntity<>(body, h, 200);
    }
}
