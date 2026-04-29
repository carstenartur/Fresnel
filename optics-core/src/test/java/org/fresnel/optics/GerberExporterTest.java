package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GerberExporterTest {

    @Test
    void emitsValidRs274xHeaderAndFooter() throws Exception {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(2.0, 50.0, 550.0, 600.0);
        String g = new String(GerberExporter.toGerberBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(g.contains("%FSLAX46Y46*%"), "format spec missing");
        assertTrue(g.contains("%MOMM*%"), "mm units missing");
        assertTrue(g.contains("%ADD10C,2.000000*%"), "outer aperture should be diameter = D = 2.0mm");
        assertTrue(g.endsWith("M02*\n"), "must end with M02 EOF");
    }

    @Test
    void apertureCountMatchesZoneCount() throws Exception {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(2.0, 50.0, 550.0, 600.0);
        String g = new String(GerberExporter.toGerberBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        long apertureDefs = g.lines().filter(l -> l.startsWith("%ADD") && l.endsWith("*%")).count();
        double R = 1.0;
        double lambdaMm = 550e-6;
        double f = 50.0;
        long zones = (long) Math.floor((R * R) / (lambdaMm * f));
        // +1 for the outer aperture-clipping disk.
        assertEquals(zones + 1, apertureDefs);
    }

    @Test
    void flashesAndPolarityToggleArePaired() throws Exception {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(0.5, 50.0, 550.0, 600.0);
        String g = new String(GerberExporter.toGerberBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        long flashes = g.lines().filter(l -> l.equals("X0Y0D03*")).count();
        long polarities = g.lines().filter(l -> l.equals("%LPD*%") || l.equals("%LPC*%")).count();
        // Each flash is preceded by a polarity command.
        assertEquals(flashes, polarities);
    }

    @Test
    void negativePolarityInvertsStartingLayer() throws Exception {
        SingleZonePlateParameters pos = SingleZonePlateParameters.onAxis(0.5, 50.0, 550.0, 600.0);
        SingleZonePlateParameters neg = new SingleZonePlateParameters(
                0.5, 50.0, 550.0, 600.0, 0.0, 0.0,
                MaskType.BINARY_AMPLITUDE, Polarity.NEGATIVE);
        String gPos = new String(GerberExporter.toGerberBytes(pos), java.nio.charset.StandardCharsets.UTF_8);
        String gNeg = new String(GerberExporter.toGerberBytes(neg), java.nio.charset.StandardCharsets.UTF_8);
        // The first polarity command after %ADD definitions reflects the OFF colour.
        int posIdx = gPos.indexOf("%LP");
        int negIdx = gNeg.indexOf("%LP");
        assertTrue(posIdx > 0 && negIdx > 0);
        assertEquals("%LPD*%", gPos.substring(posIdx, posIdx + 6));
        assertEquals("%LPC*%", gNeg.substring(negIdx, negIdx + 6));
    }
}
