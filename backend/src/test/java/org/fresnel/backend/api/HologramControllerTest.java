package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class HologramControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void synthesisesPngFromBase64TargetImage() throws Exception {
        String b64 = base64CheckerPng(32);
        String body = """
                {
                  "targetImageBase64": "%s",
                  "sidePx": 32,
                  "iterations": 5,
                  "outputType": "GREYSCALE_PHASE",
                  "dpi": 600.0
                }
                """.formatted(b64);
        MvcResult res = mvc.perform(post("/api/holograms/synthesize.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn();
        // Returned PNG should be 32x32.
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(
                res.getResponse().getContentAsByteArray()));
        org.junit.jupiter.api.Assertions.assertEquals(32, img.getWidth());
    }

    @Test
    void rejectsNonPowerOfTwoSide() throws Exception {
        String b64 = base64CheckerPng(32);
        String body = """
                {
                  "targetImageBase64": "%s",
                  "sidePx": 33,
                  "iterations": 1,
                  "dpi": 600.0
                }
                """.formatted(b64);
        // sidePx=33 fails @Min(16) is fine, but our manual power-of-two check throws IllegalArgumentException.
        // Spring maps RuntimeException to 500 by default; depending on resolver it may surface as 400.
        mvc.perform(post("/api/holograms/synthesize.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reconstructProducesPng() throws Exception {
        String b64 = base64CheckerPng(16);
        String body = """
                {
                  "targetImageBase64": "%s",
                  "sidePx": 16,
                  "iterations": 3,
                  "outputType": "GREYSCALE_PHASE",
                  "dpi": 300.0
                }
                """.formatted(b64);
        mvc.perform(post("/api/holograms/reconstruct.png?previewOnly=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void rejectsOversizedBase64Payload() throws Exception {
        // Build a base64 string just over the cap; should be rejected before decode.
        int over = HologramController.MAX_BASE64_BYTES + 8;
        StringBuilder sb = new StringBuilder(over);
        for (int i = 0; i < over; i++) sb.append('A');
        String body = """
                {
                  "targetImageBase64": "%s",
                  "sidePx": 16,
                  "iterations": 1,
                  "dpi": 300.0
                }
                """.formatted(sb);
        mvc.perform(post("/api/holograms/synthesize.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private static String base64CheckerPng(int n) throws Exception {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                img.getRaster().setSample(x, y, 0, ((x / 4) + (y / 4)) % 2 == 0 ? 220 : 30);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
