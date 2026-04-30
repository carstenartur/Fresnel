package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnitsTest {

    @Test
    void pixelSizeMmFor2400Dpi() {
        // 25.4 mm / 2400 ≈ 0.010583 mm  ≈ 10.58 µm
        assertEquals(10.5833, Units.pixelSizeMicrons(2400.0), 0.001);
    }

    @Test
    void mmToPixelsRoundsToNearest() {
        assertEquals(94, Units.mmToPixels(1.0, 2400.0));
    }

    @Test
    void rejectsNonPositiveDpi() {
        assertThrows(IllegalArgumentException.class, () -> Units.pixelSizeMm(0));
        assertThrows(IllegalArgumentException.class, () -> Units.pixelSizeMm(-100));
    }

    @Test
    void nmToMmConvertsCorrectly() {
        assertEquals(550e-6, Units.nmToMm(550.0), 1e-12);
    }
}
