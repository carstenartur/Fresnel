package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundOrientedSchlierenParametersTest {

    @Test
    void defaultsAreBoundedAndReproducible() {
        BackgroundOrientedSchlierenParameters parameters =
                BackgroundOrientedSchlierenParameters.defaults();

        assertEquals(1920, parameters.widthPx());
        assertEquals(1080, parameters.heightPx());
        assertEquals(20_260_824L, parameters.patternSeed());
        assertTrue(parameters.requestedDotCount() > 1_000);
        assertTrue(parameters.requestedDotCount()
                < BackgroundOrientedSchlierenParameters.MAX_DOTS);
    }

    @Test
    void rejectsPixelBombAndGeometryWithoutUsableArea() {
        assertThrows(IllegalArgumentException.class, () ->
                new BackgroundOrientedSchlierenParameters(
                        4096, 4096, 300, 1, 7, 0.12, 3, 64, true, false));
        assertThrows(IllegalArgumentException.class, () ->
                new BackgroundOrientedSchlierenParameters(
                        256, 256, 300, 1, 64, 0.12, 3, 100, false, false));
    }

    @Test
    void fiducialsRequireARealRegistrationBorder() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new BackgroundOrientedSchlierenParameters(
                        640, 480, 300, 1, 9, 0.10, 3, 10, true, false));
        assertTrue(exception.getMessage().contains("fiducials"));
    }

    @Test
    void rejectsNonFiniteAndUnsafeValues() {
        assertThrows(IllegalArgumentException.class, () ->
                new BackgroundOrientedSchlierenParameters(
                        640, 480, Double.NaN, 1, 7, 0.12, 3, 64, true, false));
        assertThrows(IllegalArgumentException.class, () ->
                new BackgroundOrientedSchlierenParameters(
                        640, 480, 300, 1, 7, 0.0, 3, 64, true, false));
        assertThrows(IllegalArgumentException.class, () ->
                new BackgroundOrientedSchlierenParameters(
                        640, 480, 300, 1, 7, 0.12, -1, 64, true, false));
    }
}
