package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the analytical lattice-inversion lookup
 * ({@link HexMacroCellRenderer#nearestLatticeContaining}) returns the same centre
 * as a linear scan of {@link HexMacroCellRenderer#hexLatticeCentresInsideHex}
 * for every plausible query point.
 */
class HexLatticeIndexTest {

    private static double[] linearScan(double x, double y, List<double[]> centres, double rSq) {
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] c : centres) {
            double dx = x - c[0];
            double dy = y - c[1];
            double d = dx * dx + dy * dy;
            if (d <= rSq && d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    @Test
    void analyticalAgreesWithLinearScanForRandomPointsInsideHex() {
        double R = 10.0;
        double pitch = 1.5;
        double subRadius = 0.5;
        double rSq = subRadius * subRadius;
        List<double[]> centres = HexMacroCellRenderer.hexLatticeCentresInsideHex(R, pitch);
        assertTrue(centres.size() > 50, "should generate a non-trivial lattice");

        Random rnd = new Random(42L);
        int total = 5000;
        int agree = 0;
        for (int n = 0; n < total; n++) {
            double x = (rnd.nextDouble() * 2 - 1) * R;
            double y = (rnd.nextDouble() * 2 - 1) * R;
            double[] linear = linearScan(x, y, centres, rSq);
            double[] analytic = HexMacroCellRenderer.nearestLatticeContaining(x, y, pitch, rSq, R);
            if (linear == null && analytic == null) { agree++; continue; }
            if (linear == null || analytic == null) {
                fail("disagreement at (" + x + "," + y + "): linear="
                        + (linear == null ? "null" : "(" + linear[0] + "," + linear[1] + ")")
                        + " analytic="
                        + (analytic == null ? "null" : "(" + analytic[0] + "," + analytic[1] + ")"));
            }
            assertEquals(linear[0], analytic[0], 1e-9, "cx mismatch at (" + x + "," + y + ")");
            assertEquals(linear[1], analytic[1], 1e-9, "cy mismatch at (" + x + "," + y + ")");
            agree++;
        }
        assertEquals(total, agree);
    }

    @Test
    void analyticalReturnsNullForPointsClearlyOutsideAnyDisc() {
        double R = 5.0;
        double pitch = 2.0;
        double subRadius = 0.5;
        double rSq = subRadius * subRadius;
        // Point exactly on a row but between two columns, outside any sub-disc.
        double[] hit = HexMacroCellRenderer.nearestLatticeContaining(1.0, 0.0, pitch, rSq, R);
        assertNull(hit);
    }
}
