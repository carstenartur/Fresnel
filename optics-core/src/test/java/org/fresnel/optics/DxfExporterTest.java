package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxfExporterTest {

    @Test
    void writesHeaderAperturesAndFooter() throws Exception {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(10.0, 50.0, 550.0, 600.0);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DxfExporter.writeZonePlate(p, baos);
        String dxf = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(dxf.startsWith("0\nSECTION\n2\nENTITIES\n"), "should start with ENTITIES section");
        assertTrue(dxf.endsWith("0\nENDSEC\n0\nEOF\n"), "should end with ENDSEC + EOF");
        // First circle is the outer aperture.
        assertTrue(dxf.contains("0\nCIRCLE\n8\n0\n10\n0.000000\n20\n0.000000\n30\n0.0\n40\n5.000000\n"),
                "should emit the aperture-clipping circle of radius 5mm");
    }

    @Test
    void emitsExpectedNumberOfCircles() throws Exception {
        // R²/(λ·f) zones plus the aperture-clipping circle.
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(10.0, 50.0, 550.0, 600.0);
        byte[] bytes = DxfExporter.toDxfBytes(p);
        String dxf = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        long circles = dxf.lines().filter(l -> l.equals("CIRCLE")).count();
        // n_max = R²/(λ·f) = 25 / (5.5e-4) ≈ 909, plus the outer aperture circle.
        double R = 5.0;
        double lambdaMm = 550e-6;
        double f = 50.0;
        long expectedZones = (long) Math.floor((R * R) / (lambdaMm * f));
        assertEquals(expectedZones + 1, circles);
    }

    @Test
    void honoursOffAxisOffset() throws Exception {
        SingleZonePlateParameters p = new SingleZonePlateParameters(
                10.0, 50.0, 550.0, 600.0, 1.5, -2.5,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
        String dxf = new String(DxfExporter.toDxfBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(dxf.contains("\n10\n1.500000\n20\n-2.500000\n"),
                "circles should be centred at the off-axis target");
    }
}
