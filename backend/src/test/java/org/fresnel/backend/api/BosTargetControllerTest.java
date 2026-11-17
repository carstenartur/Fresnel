package org.fresnel.backend.api;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BosTargetControllerTest {

    private static final String BASE =
            "/api/measurements/background-oriented-schlieren/target";

    private static final String VALID = """
            {
              "widthPx": 640,
              "heightPx": 480,
              "intendedDpi": 100.0,
              "patternSeed": 20260823,
              "dotDiameterPx": 5,
              "targetFillRatio": 0.10,
              "minimumDotSpacingPx": 2,
              "borderPx": 32,
              "fiducialsEnabled": true,
              "invertPattern": false
            }
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void publicManifestIsDeterministicAndContainsReproductionEvidence() throws Exception {
        MvcResult first = mvc.perform(post(BASE + "/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.algorithmVersion")
                        .value("background-oriented-schlieren-target/1"))
                .andExpect(jsonPath("$.targetId").value(matchesPattern("bos-[0-9a-f]{12}")))
                .andExpect(jsonPath("$.semanticSha256")
                        .value(matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.widthPx").value(640))
                .andExpect(jsonPath("$.heightPx").value(480))
                .andExpect(jsonPath("$.activeRegion.x").value(32))
                .andExpect(jsonPath("$.activeRegion.y").value(32))
                .andExpect(jsonPath("$.activeRegion.width").value(576))
                .andExpect(jsonPath("$.activeRegion.height").value(416))
                .andExpect(jsonPath("$.fiducials.length()").value(4))
                .andReturn();

        MvcResult second = mvc.perform(post(BASE + "/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = mapper.readTree(first.getResponse().getContentAsByteArray());
        JsonNode secondJson = mapper.readTree(second.getResponse().getContentAsByteArray());
        assertThat(firstJson).isEqualTo(secondJson);
        assertThat(firstJson.get("actualFillRatio").doubleValue()).isBetween(0.08, 0.12);
        assertThat(firstJson.get("dotCount").intValue()).isPositive();
    }

    @Test
    void publicPreviewIsInlineChecksummedAndByteDeterministic() throws Exception {
        MvcResult first = mvc.perform(post(BASE + "/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("X-Fresnel-Pattern-Seed", "20260823"))
                .andExpect(header().string("X-Fresnel-Dot-Count", matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string("X-Fresnel-Achieved-Fill-Ratio", matchesPattern("0[.][0-9]+")))
                .andExpect(header().string(HttpHeaders.ETAG,
                        matchesPattern("\"[0-9a-f]{64}\"")))
                .andExpect(header().string(BosTargetController.TARGET_SHA256_HEADER,
                        matchesPattern("[0-9a-f]{64}")))
                .andExpect(header().string(BosTargetController.TARGET_ID_HEADER,
                        matchesPattern("bos-[0-9a-f]{12}")))
                .andExpect(header().string(BosTargetController.ACTIVE_REGION_HEADER,
                        "32,32,576,416"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        matchesPattern("inline; filename=\"bos-[0-9a-f]{12}\\.png\"")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        MvcResult second = mvc.perform(post(BASE + "/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andReturn();

        byte[] firstBytes = first.getResponse().getContentAsByteArray();
        assertThat(firstBytes).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(second.getResponse().getContentAsByteArray()).isEqualTo(firstBytes);
        assertThat(second.getResponse().getHeader(BosTargetController.TARGET_SHA256_HEADER))
                .isEqualTo(first.getResponse().getHeader(BosTargetController.TARGET_SHA256_HEADER));
    }

    @Test
    void optionalThumbnailKeepsSourceIdentityAndCapsLongestSide() throws Exception {
        String large = VALID.replace("640", "1920").replace("480", "1080");
        var source = mvc.perform(post(BASE + "/preview.png")
                        .contentType(MediaType.APPLICATION_JSON).content(large))
                .andExpect(status().isOk()).andReturn().getResponse();
        var thumbnail = mvc.perform(post(BASE + "/preview.png").param("thumbnail", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(large))
                .andExpect(status().isOk()).andReturn().getResponse();
        var nativeImage = ImageIO.read(new ByteArrayInputStream(source.getContentAsByteArray()));
        var smallImage = ImageIO.read(new ByteArrayInputStream(thumbnail.getContentAsByteArray()));
        assertThat(nativeImage.getWidth()).isEqualTo(1920);
        assertThat(smallImage.getWidth()).isEqualTo(1600);
        assertThat(smallImage.getHeight()).isEqualTo(900);
        assertThat(thumbnail.getHeader(BosTargetController.TARGET_SHA256_HEADER))
                .isEqualTo(source.getHeader(BosTargetController.TARGET_SHA256_HEADER));
        assertThat(thumbnail.getHeader(HttpHeaders.ETAG)).isNotEqualTo(source.getHeader(HttpHeaders.ETAG));
    }

    @Test
    void svgPrintHasExactPhysicalDimensionsAndAnOptionalSeparateRuler() throws Exception {
        mvc.perform(post(BASE + "/export.svg").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        for (boolean strip : new boolean[]{false, true}) {
            var response = mvc.perform(post(BASE + "/export.svg").with(user("alice").roles("USER"))
                            .param("calibrationStrip", Boolean.toString(strip))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID))
                    .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                    .andReturn().getResponse();
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(response.getContentAsByteArray()));
            var svg = doc.getDocumentElement();
            assertThat(Double.parseDouble(svg.getAttribute("width").replace("mm", ""))).isCloseTo(162.56, within(0.00001));
            assertThat(Double.parseDouble(svg.getAttribute("height").replace("mm", "")))
                    .isCloseTo(121.92 + (strip ? 12 : 0), within(0.00001));
            assertThat(svg.getTextContent()).contains(response.getHeader(BosTargetController.TARGET_ID_HEADER));
            assertThat(doc.getElementsByTagName("path").getLength()).isPositive();
            assertThat(doc.getElementsByTagName("image").getLength()).isZero();
            var rulers = doc.getElementsByTagName("line");
            if (strip) {
                var ruler = (org.w3c.dom.Element) rulers.item(0);
                double x1 = Double.parseDouble(ruler.getAttribute("x1"));
                double x2 = Double.parseDouble(ruler.getAttribute("x2"));
                assertThat(x2 - x1).isEqualTo(50.0);
                assertThat(Double.parseDouble(ruler.getAttribute("y1"))).isGreaterThan(121.92);
            } else {
                assertThat(rulers.getLength()).isZero();
            }
        }
    }

    @Test
    void pdfPrintRetainsTargetSizeAndShowsTheTargetIdAndScale() throws Exception {
        mvc.perform(post(BASE + "/export.pdf").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        var response = mvc.perform(post(BASE + "/export.pdf").with(user("alice").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse();
        try (var doc = Loader.loadPDF(response.getContentAsByteArray())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            var page = doc.getPage(0);
            // 640 x 480 source pixels at 100 dpi, plus a 12 mm detachable strip.
            assertThat((double) page.getMediaBox().getWidth()).isCloseTo(640.0 * 72 / 100, within(0.001));
            assertThat((double) page.getMediaBox().getHeight()).isCloseTo(480.0 * 72 / 100 + 12 * 72 / 25.4, within(0.001));
            assertThat(new PDFTextStripper().getText(doc)).contains("50 mm", response.getHeader(BosTargetController.TARGET_ID_HEADER));
        }
    }

    @Test
    void productionDownloadRequiresAuthenticationAndUsesAttachmentDisposition() throws Exception {
        mvc.perform(post(BASE + "/export.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isUnauthorized());

        MvcResult export = mvc.perform(post(BASE + "/export.png")
                        .with(user("alice").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        matchesPattern("attachment; filename=\"bos-[0-9a-f]{12}\\.png\"")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        assertThat(export.getResponse().getContentAsByteArray())
                .startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void structuralAndCrossFieldResourceLimitsFailClosed() throws Exception {
        mvc.perform(post(BASE + "/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"widthPx\": 640", "\"widthPx\": 319")))
                .andExpect(status().isBadRequest());

        String excessivePixels = VALID
                .replace("\"widthPx\": 640", "\"widthPx\": 5000")
                .replace("\"heightPx\": 480", "\"heightPx\": 5000");
        mvc.perform(post(BASE + "/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(excessivePixels))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("maximum")));

        String impossibleBorder = VALID.replace("\"borderPx\": 32", "\"borderPx\": 200");
        mvc.perform(post(BASE + "/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(impossibleBorder))
                .andExpect(status().isBadRequest());
    }
}
