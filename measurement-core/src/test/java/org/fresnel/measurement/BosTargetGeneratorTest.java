package org.fresnel.measurement;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BosTargetGeneratorTest {

    @Test
    void defaultsGenerateAReproducibleTargetAndManifest() {
        BosTargetParameters parameters = BosTargetParameters.defaults();
        BosTarget first = BosTargetGenerator.generate(parameters);
        BosTarget second = BosTargetGenerator.generate(parameters);

        assertEquals(parameters, first.parameters());
        assertEquals("background-oriented-schlieren-target/1",
                first.manifest().algorithmVersion());
        assertEquals(first.semanticSha256(), second.semanticSha256());
        assertEquals(first.targetId(), second.targetId());
        assertArrayEquals(first.grayscalePixels(), second.grayscalePixels());
        assertEquals(64, first.semanticSha256().length());
        assertTrue(first.targetId().matches("bos-[0-9a-f]{12}"));
        assertEquals(parameters.widthPx() * parameters.heightPx(),
                first.grayscalePixels().length);
        assertEquals(parameters.borderPx(), first.activeRegion().x());
        assertEquals(parameters.borderPx(), first.activeRegion().y());
        assertEquals(parameters.activeWidthPx(), first.activeRegion().width());
        assertEquals(parameters.activeHeightPx(), first.activeRegion().height());
        assertEquals(4, first.fiducials().size());
        assertEquals(List.of(
                        BosTarget.FiducialShape.SOLID_SQUARE,
                        BosTarget.FiducialShape.RING,
                        BosTarget.FiducialShape.L_SHAPE,
                        BosTarget.FiducialShape.CROSS),
                first.fiducials().stream().map(BosTarget.Fiducial::shape).toList());
    }

    @Test
    void changingSeedChangesThePatternButNotItsGeometry() {
        BosTargetParameters defaults = BosTargetParameters.defaults();
        BosTarget first = BosTargetGenerator.generate(defaults);
        BosTarget second = BosTargetGenerator.generate(copy(defaults, defaults.patternSeed() + 1, false));

        assertNotEquals(first.semanticSha256(), second.semanticSha256());
        assertFalse(Arrays.equals(first.grayscalePixels(), second.grayscalePixels()));
        assertEquals(first.activeRegion(), second.activeRegion());
        assertEquals(first.dotCount(), second.dotCount());
        assertEquals(first.cellPitchPx(), second.cellPitchPx());
    }

    @Test
    void actualPixelFillCloselyTracksTheRequestedRatio() {
        BosTarget target = BosTargetGenerator.generate(BosTargetParameters.defaults());
        assertEquals(target.parameters().targetFillRatio(), target.actualFillRatio(), 0.012);
        assertTrue(target.dotCount() > 100);
        assertTrue(target.cellPitchPx()
                >= target.parameters().dotDiameterPx()
                + target.parameters().minimumDotSpacingPx());
        assertEquals(target.actualFillRatio(), target.manifest().actualFillRatio());
    }

    @Test
    void activeDotsRemainInsideTheAnalysisRegionAndBorderIsQuietWithoutFiducials() {
        BosTargetParameters defaults = BosTargetParameters.defaults();
        BosTargetParameters parameters = new BosTargetParameters(
                defaults.widthPx(),
                defaults.heightPx(),
                defaults.intendedDpi(),
                defaults.patternSeed(),
                defaults.dotDiameterPx(),
                defaults.targetFillRatio(),
                defaults.minimumDotSpacingPx(),
                defaults.borderPx(),
                false,
                false);
        BosTarget target = BosTargetGenerator.generate(parameters);
        BosTarget.Region active = target.activeRegion();

        assertTrue(target.fiducials().isEmpty());
        for (int y = 0; y < parameters.heightPx(); y++) {
            for (int x = 0; x < parameters.widthPx(); x++) {
                if (!active.contains(x, y)) {
                    assertEquals(255, target.pixelUnsigned(x, y),
                            () -> "unexpected foreground in quiet border at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void fiducialsAreOutsideTheAnalysisRegionAndAsymmetric() {
        BosTarget target = BosTargetGenerator.generate(BosTargetParameters.defaults());
        BosTarget.Region active = target.activeRegion();
        int[] foregroundCounts = new int[target.fiducials().size()];

        for (int index = 0; index < target.fiducials().size(); index++) {
            BosTarget.Fiducial fiducial = target.fiducials().get(index);
            BosTarget.Region region = fiducial.region();
            assertFalse(rectanglesOverlap(active, region));
            for (int y = region.y(); y < region.maxYExclusive(); y++) {
                for (int x = region.x(); x < region.maxXExclusive(); x++) {
                    if (target.pixelUnsigned(x, y) == 0) foregroundCounts[index]++;
                }
            }
        }

        assertEquals(target.fiducials().size(), Arrays.stream(foregroundCounts).distinct().count(),
                "each marker shape should have a distinct foreground area");
    }

    @Test
    void inversionComplementsEveryGeneratedPixel() {
        BosTargetParameters defaults = BosTargetParameters.defaults();
        BosTarget normal = BosTargetGenerator.generate(defaults);
        BosTarget inverted = BosTargetGenerator.generate(copy(defaults, defaults.patternSeed(), true));
        byte[] normalPixels = normal.grayscalePixels();
        byte[] invertedPixels = inverted.grayscalePixels();

        assertEquals(normalPixels.length, invertedPixels.length);
        for (int i = 0; i < normalPixels.length; i++) {
            assertEquals(255,
                    Byte.toUnsignedInt(normalPixels[i]) + Byte.toUnsignedInt(invertedPixels[i]));
        }
        assertNotEquals(normal.semanticSha256(), inverted.semanticSha256());
    }

    @Test
    void pixelAndManifestAccessAreDefensiveAndPhysicallyScaled() {
        BosTarget target = BosTargetGenerator.generate(BosTargetParameters.defaults());
        byte[] copy = target.grayscalePixels();
        int original = target.pixelUnsigned(0, 0);
        copy[0] = (byte) (255 - original);

        assertEquals(original, target.pixelUnsigned(0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> target.pixelUnsigned(-1, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> target.pixelUnsigned(target.parameters().widthPx(), 0));
        assertEquals(target.parameters().intendedWidthMm(),
                target.manifest().intendedWidthMm(), 1e-12);
        assertEquals(target.parameters().intendedHeightMm(),
                target.manifest().intendedHeightMm(), 1e-12);
        assertThrows(UnsupportedOperationException.class, target.fiducials()::clear);
        assertThrows(UnsupportedOperationException.class, target.manifest().fiducials()::clear);
    }

    @Test
    void parameterValidationRejectsResourceAndGeometryAbuse() {
        BosTargetParameters d = BosTargetParameters.defaults();
        assertThrows(IllegalArgumentException.class, () -> parameters(319, d.heightPx(), d));
        assertThrows(IllegalArgumentException.class, () -> parameters(6001, d.heightPx(), d));
        assertThrows(IllegalArgumentException.class, () -> parameters(d.widthPx(), 239, d));
        assertThrows(IllegalArgumentException.class, () -> parameters(d.widthPx(), 6001, d));
        assertThrows(IllegalArgumentException.class, () -> parameters(5000, 5000, d));
        assertThrows(IllegalArgumentException.class, () -> new BosTargetParameters(
                d.widthPx(), d.heightPx(), 49, d.patternSeed(), d.dotDiameterPx(),
                d.targetFillRatio(), d.minimumDotSpacingPx(), d.borderPx(), true, false));
        assertThrows(IllegalArgumentException.class, () -> new BosTargetParameters(
                d.widthPx(), d.heightPx(), d.intendedDpi(), d.patternSeed(), 1,
                d.targetFillRatio(), d.minimumDotSpacingPx(), d.borderPx(), true, false));
        assertThrows(IllegalArgumentException.class, () -> new BosTargetParameters(
                d.widthPx(), d.heightPx(), d.intendedDpi(), d.patternSeed(), d.dotDiameterPx(),
                0.01, d.minimumDotSpacingPx(), d.borderPx(), true, false));
        assertThrows(IllegalArgumentException.class, () -> new BosTargetParameters(
                d.widthPx(), d.heightPx(), d.intendedDpi(), d.patternSeed(), d.dotDiameterPx(),
                d.targetFillRatio(), 0, d.borderPx(), true, false));
        assertThrows(IllegalArgumentException.class, () -> new BosTargetParameters(
                d.widthPx(), d.heightPx(), d.intendedDpi(), d.patternSeed(), d.dotDiameterPx(),
                d.targetFillRatio(), d.minimumDotSpacingPx(), 23, true, false));
        assertThrows(IllegalArgumentException.class, () -> new BosTargetParameters(
                320, 240, d.intendedDpi(), d.patternSeed(), d.dotDiameterPx(),
                d.targetFillRatio(), d.minimumDotSpacingPx(), 80, false, false));
        assertThrows(IllegalArgumentException.class, () -> new BosTargetParameters(
                320, 240, d.intendedDpi(), d.patternSeed(), 32,
                0.25, 32, 64, true, false));
    }

    @Test
    void regionsValidateBoundsAndContainment() {
        BosTarget.Region region = new BosTarget.Region(2, 3, 4, 5);
        assertTrue(region.contains(2, 3));
        assertTrue(region.contains(5, 7));
        assertFalse(region.contains(6, 7));
        assertFalse(region.contains(5, 8));
        assertEquals(6, region.maxXExclusive());
        assertEquals(8, region.maxYExclusive());
        assertThrows(IllegalArgumentException.class, () -> new BosTarget.Region(-1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BosTarget.Region(0, 0, 0, 1));
    }

    private static boolean rectanglesOverlap(BosTarget.Region a, BosTarget.Region b) {
        return a.x() < b.maxXExclusive()
                && a.maxXExclusive() > b.x()
                && a.y() < b.maxYExclusive()
                && a.maxYExclusive() > b.y();
    }

    private static BosTargetParameters parameters(int width, int height, BosTargetParameters d) {
        return new BosTargetParameters(
                width, height, d.intendedDpi(), d.patternSeed(), d.dotDiameterPx(),
                d.targetFillRatio(), d.minimumDotSpacingPx(), d.borderPx(), true, false);
    }

    private static BosTargetParameters copy(
            BosTargetParameters d, long seed, boolean inverted) {
        return new BosTargetParameters(
                d.widthPx(), d.heightPx(), d.intendedDpi(), seed, d.dotDiameterPx(),
                d.targetFillRatio(), d.minimumDotSpacingPx(), d.borderPx(),
                d.fiducialsEnabled(), inverted);
    }
}
