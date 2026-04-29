package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SvgExporterTest {

    @Test
    void analyticZonePlateSvgContainsConcentricCircles() throws Exception {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(10.0, 100.0, 550.0, 600.0);
        byte[] svg = SvgExporter.toSvgZonePlateBytes(p);
        String s = new String(svg, StandardCharsets.UTF_8);
        assertTrue(s.startsWith("<?xml"), "missing XML prolog");
        assertTrue(s.contains("<svg"), "no svg root");
        assertTrue(s.contains("width=\"10.0000mm\""), "wrong width");
        long circles = s.lines().filter(l -> l.contains("<circle")).count();
        assertTrue(circles >= 5, "expected several rings, got " + circles);
        assertTrue(s.endsWith("</svg>\n"));
    }

    @Test
    void rasterEmbedSvgContainsBase64Png() throws Exception {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(2.0, 50.0, 550.0, 300.0);
        RenderResult r = ZonePlateRenderer.render(p);
        byte[] svg = SvgExporter.toSvgRasterBytes(r, 300.0);
        String s = new String(svg, StandardCharsets.UTF_8);
        assertTrue(s.contains("data:image/png;base64,"));
        assertTrue(s.contains("mm\""), "no millimetre dimensions");
        // Verify it parses as well-formed XML.
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.newDocumentBuilder().parse(new ByteArrayInputStream(svg));
    }
}
