package org.fresnel.measurement;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable generated BOS target together with the metadata needed to reproduce,
 * display, print and later detect it in a camera frame.
 */
public final class BosTarget {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final BosTargetParameters parameters;
    private final Region activeRegion;
    private final int dotCount;
    private final double actualFillRatio;
    private final int cellPitchPx;
    private final List<Fiducial> fiducials;
    private final byte[] grayscalePixels;
    private final String semanticSha256;
    private final String targetId;

    BosTarget(
            BosTargetParameters parameters,
            Region activeRegion,
            int dotCount,
            double actualFillRatio,
            int cellPitchPx,
            List<Fiducial> fiducials,
            byte[] grayscalePixels,
            String semanticSha256) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.activeRegion = Objects.requireNonNull(activeRegion, "activeRegion");
        if (dotCount < 1) throw new IllegalArgumentException("dotCount must be positive");
        if (!Double.isFinite(actualFillRatio)
                || actualFillRatio <= 0.0
                || actualFillRatio >= 1.0) {
            throw new IllegalArgumentException("actualFillRatio must be between zero and one");
        }
        if (cellPitchPx < parameters.dotDiameterPx() + parameters.minimumDotSpacingPx()) {
            throw new IllegalArgumentException("cellPitchPx violates the minimum dot spacing");
        }
        this.dotCount = dotCount;
        this.actualFillRatio = actualFillRatio;
        this.cellPitchPx = cellPitchPx;
        this.fiducials = List.copyOf(Objects.requireNonNull(fiducials, "fiducials"));
        Objects.requireNonNull(grayscalePixels, "grayscalePixels");
        int expectedPixels = Math.multiplyExact(parameters.widthPx(), parameters.heightPx());
        if (grayscalePixels.length != expectedPixels) {
            throw new IllegalArgumentException(
                    "pixel length mismatch: expected " + expectedPixels
                            + " but was " + grayscalePixels.length);
        }
        this.grayscalePixels = grayscalePixels.clone();
        if (semanticSha256 == null || !SHA_256.matcher(semanticSha256).matches()) {
            throw new IllegalArgumentException("semanticSha256 must be lowercase SHA-256");
        }
        this.semanticSha256 = semanticSha256;
        this.targetId = "bos-" + semanticSha256.substring(0, 12);
    }

    public BosTargetParameters parameters() {
        return parameters;
    }

    public Region activeRegion() {
        return activeRegion;
    }

    public int dotCount() {
        return dotCount;
    }

    public double actualFillRatio() {
        return actualFillRatio;
    }

    public int cellPitchPx() {
        return cellPitchPx;
    }

    public List<Fiducial> fiducials() {
        return fiducials;
    }

    public byte[] grayscalePixels() {
        return grayscalePixels.clone();
    }

    public String semanticSha256() {
        return semanticSha256;
    }

    public String targetId() {
        return targetId;
    }

    public int pixelUnsigned(int x, int y) {
        if (x < 0 || x >= parameters.widthPx() || y < 0 || y >= parameters.heightPx()) {
            throw new IndexOutOfBoundsException("pixel outside target: " + x + "," + y);
        }
        return Byte.toUnsignedInt(grayscalePixels[y * parameters.widthPx() + x]);
    }

    public Manifest manifest() {
        return new Manifest(
                BosTargetParameters.ALGORITHM_VERSION,
                targetId,
                semanticSha256,
                parameters.widthPx(),
                parameters.heightPx(),
                parameters.intendedDpi(),
                parameters.intendedWidthMm(),
                parameters.intendedHeightMm(),
                parameters.patternSeed(),
                parameters.dotDiameterPx(),
                parameters.targetFillRatio(),
                actualFillRatio,
                parameters.minimumDotSpacingPx(),
                cellPitchPx,
                dotCount,
                parameters.borderPx(),
                parameters.fiducialsEnabled(),
                parameters.invertPattern(),
                activeRegion,
                fiducials);
    }

    /** Inclusive-origin, exclusive-size pixel rectangle. */
    public record Region(int x, int y, int width, int height) {
        public Region {
            if (x < 0 || y < 0) throw new IllegalArgumentException("region origin must not be negative");
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("region dimensions must be positive");
            }
        }

        public int maxXExclusive() {
            return Math.addExact(x, width);
        }

        public int maxYExclusive() {
            return Math.addExact(y, height);
        }

        public boolean contains(int px, int py) {
            return px >= x && px < maxXExclusive() && py >= y && py < maxYExclusive();
        }
    }

    public enum FiducialShape {
        SOLID_SQUARE,
        RING,
        L_SHAPE,
        CROSS
    }

    /** Asymmetric marker outside the analysis region. */
    public record Fiducial(String id, FiducialShape shape, Region region) {
        public Fiducial {
            if (id == null || !id.matches("[a-z][a-z0-9-]*")) {
                throw new IllegalArgumentException("fiducial id is invalid");
            }
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(region, "region");
        }
    }

    /** Data-only manifest safe for REST responses and experiment provenance. */
    public record Manifest(
            String algorithmVersion,
            String targetId,
            String semanticSha256,
            int widthPx,
            int heightPx,
            double intendedDpi,
            double intendedWidthMm,
            double intendedHeightMm,
            long patternSeed,
            int dotDiameterPx,
            double requestedFillRatio,
            double actualFillRatio,
            int minimumDotSpacingPx,
            int cellPitchPx,
            int dotCount,
            int borderPx,
            boolean fiducialsEnabled,
            boolean invertPattern,
            Region activeRegion,
            List<Fiducial> fiducials) {

        public Manifest {
            Objects.requireNonNull(algorithmVersion, "algorithmVersion");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(semanticSha256, "semanticSha256");
            Objects.requireNonNull(activeRegion, "activeRegion");
            fiducials = List.copyOf(Objects.requireNonNull(fiducials, "fiducials"));
        }
    }
}
