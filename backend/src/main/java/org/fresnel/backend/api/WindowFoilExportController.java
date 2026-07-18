package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.fresnel.optics.WindowFoilRenderer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Production exports for Window Foil that are also advertised in PluginRegistry. */
@RestController
@RequestMapping("/api/designs/foil")
public class WindowFoilExportController {

    @PostMapping(
            value = "/export.png",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> exportPng(
            @Valid @RequestBody WindowFoilRequest request) throws IOException {
        RenderResult rendered = WindowFoilRenderer.render(request.toParameters());
        byte[] content = PngExporter.toPngBytes(rendered, request.dpi());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("fresnel-window-foil.png")
                .build());
        return ResponseEntity.ok().headers(headers).body(content);
    }
}
