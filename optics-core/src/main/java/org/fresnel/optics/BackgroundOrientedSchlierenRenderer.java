package org.fresnel.optics;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Deterministic random-dot target renderer for background-oriented schlieren.
 *
 * <p>Dots are placed with a spatial index. The renderer never silently relaxes
 * the requested diameter or spacing: an infeasible density is rejected with a
 * stable error instead of producing a misleading target.</p>
 */
public final class BackgroundOrientedSchlierenRenderer {

    private static final int MAX_ATTEMPTS_PER_DOT = 80;

    private BackgroundOrientedSchlierenRenderer() {}

    public static Result render(BackgroundOrientedSchlierenParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }

        List<Dot> dots = placeDots(parameters);
        BufferedImage image = new BufferedImage(
                parameters.widthPx(),
                parameters.heightPx(),
                BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            Color background = parameters.invertPattern() ? Color.BLACK : Color.WHITE;
            Color foreground = parameters.invertPattern() ? Color.WHITE : Color.BLACK;
            graphics.setColor(background);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(foreground);
            int diameter = parameters.dotDiameterPx();
            int radiusFloor = diameter / 2;
            for (Dot dot : dots) {
                graphics.fillOval(
                        dot.centerX() - radiusFloor,
                        dot.centerY() - radiusFloor,
                        diameter,
                        diameter);
            }
            if (parameters.fiducialsEnabled()) {
                drawFiducials(graphics, parameters);
            }
        } finally {
            graphics.dispose();
        }

        double dotArea = Math.PI * Math.pow(parameters.dotDiameterPx() / 2.0, 2);
        double usableArea =
                (double) parameters.usableWidthPx() * parameters.usableHeightPx();
        return new Result(
                image,
                dots.size(),
                dots.size() * dotArea / usableArea);
    }

    static List<Dot> placeDots(BackgroundOrientedSchlierenParameters parameters) {
        int target = parameters.requestedDotCount();
        int diameter = parameters.dotDiameterPx();
        int radiusFloor = diameter / 2;
        int radiusCeil = diameter - radiusFloor;
        int minimumCenterDistance = diameter + parameters.minimumDotSpacingPx();
        int cellSize = Math.max(1, minimumCenterDistance);
        int columns = Math.max(1, (parameters.widthPx() + cellSize - 1) / cellSize);
        int rows = Math.max(1, (parameters.heightPx() + cellSize - 1) / cellSize);

        @SuppressWarnings("unchecked")
        List<Dot>[] cells = (List<Dot>[]) new List<?>[Math.multiplyExact(columns, rows)];
        List<Dot> dots = new ArrayList<>(target);
        SplittableRandom random = new SplittableRandom(parameters.patternSeed());

        int minX = parameters.borderPx() + radiusFloor;
        int maxX = parameters.widthPx() - parameters.borderPx() - radiusCeil;
        int minY = parameters.borderPx() + radiusFloor;
        int maxY = parameters.heightPx() - parameters.borderPx() - radiusCeil;
        if (maxX < minX || maxY < minY) {
            throw new IllegalArgumentException("BOS target has no valid dot-center area");
        }

        long maximumAttempts = Math.max(
                10_000L,
                Math.min(40_000_000L, (long) target * MAX_ATTEMPTS_PER_DOT));
        long minimumDistanceSquared =
                (long) minimumCenterDistance * minimumCenterDistance;
        for (long attempt = 0; attempt < maximumAttempts && dots.size() < target; attempt++) {
            int x = random.nextInt(minX, maxX + 1);
            int y = random.nextInt(minY, maxY + 1);
            int cellX = x / cellSize;
            int cellY = y / cellSize;
            if (!isAvailable(
                    cells,
                    columns,
                    rows,
                    cellX,
                    cellY,
                    x,
                    y,
                    minimumDistanceSquared)) {
                continue;
            }
            Dot dot = new Dot(x, y);
            dots.add(dot);
            int index = cellY * columns + cellX;
            List<Dot> bucket = cells[index];
            if (bucket == null) {
                bucket = new ArrayList<>(1);
                cells[index] = bucket;
            }
            bucket.add(dot);
        }

        if (dots.size() != target) {
            throw new IllegalArgumentException(
                    "Requested BOS fill ratio is infeasible with the selected dot "
                            + "diameter and spacing: placed " + dots.size()
                            + " of " + target + " dots");
        }
        return List.copyOf(dots);
    }

    private static boolean isAvailable(
            List<Dot>[] cells,
            int columns,
            int rows,
            int cellX,
            int cellY,
            int x,
            int y,
            long minimumDistanceSquared) {
        for (int candidateY = Math.max(0, cellY - 1);
                candidateY <= Math.min(rows - 1, cellY + 1);
                candidateY++) {
            for (int candidateX = Math.max(0, cellX - 1);
                    candidateX <= Math.min(columns - 1, cellX + 1);
                    candidateX++) {
                List<Dot> bucket = cells[candidateY * columns + candidateX];
                if (bucket == null) continue;
                for (Dot existing : bucket) {
                    long dx = (long) existing.centerX() - x;
                    long dy = (long) existing.centerY() - y;
                    if (dx * dx + dy * dy < minimumDistanceSquared) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void drawFiducials(
            Graphics2D graphics,
            BackgroundOrientedSchlierenParameters parameters) {
        int border = parameters.borderPx();
        int arm = Math.max(8, Math.min(border - 4, parameters.dotDiameterPx() * 3));
        int thickness = Math.max(2, parameters.dotDiameterPx() / 3);
        int inset = Math.max(2, (border - arm) / 2);

        drawCorner(graphics, inset, inset, arm, thickness, false, false);
        drawCorner(
                graphics,
                parameters.widthPx() - inset,
                inset,
                arm,
                thickness,
                true,
                false);
        drawCorner(
                graphics,
                inset,
                parameters.heightPx() - inset,
                arm,
                thickness,
                false,
                true);
        drawCorner(
                graphics,
                parameters.widthPx() - inset,
                parameters.heightPx() - inset,
                arm,
                thickness,
                true,
                true);
    }

    private static void drawCorner(
            Graphics2D graphics,
            int anchorX,
            int anchorY,
            int arm,
            int thickness,
            boolean leftFacing,
            boolean upFacing) {
        int horizontalX = leftFacing ? anchorX - arm : anchorX;
        int horizontalY = upFacing ? anchorY - thickness : anchorY;
        int verticalX = leftFacing ? anchorX - thickness : anchorX;
        int verticalY = upFacing ? anchorY - arm : anchorY;
        graphics.fillRect(horizontalX, horizontalY, arm, thickness);
        graphics.fillRect(verticalX, verticalY, thickness, arm);
    }

    public record Result(
            BufferedImage image,
            int dotCount,
            double achievedFillRatio
    ) {
        public Result {
            if (image == null) throw new IllegalArgumentException("image must not be null");
            if (dotCount < 1) throw new IllegalArgumentException("dotCount must be positive");
            if (!Double.isFinite(achievedFillRatio) || achievedFillRatio <= 0.0) {
                throw new IllegalArgumentException(
                        "achievedFillRatio must be finite and positive");
            }
        }
    }

    record Dot(int centerX, int centerY) {}
}
