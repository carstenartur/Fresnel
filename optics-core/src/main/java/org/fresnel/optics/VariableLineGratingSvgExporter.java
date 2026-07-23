package org.fresnel.optics;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Deterministic vector SVG export built from the shared accumulated-phase model. */
public final class VariableLineGratingSvgExporter {

    private VariableLineGratingSvgExporter() {}

    public static byte[] toSvgBytes(VariableLineGratingParameters p) {
        VariableLineGratingModel.Layout layout = VariableLineGratingModel.layout(p);
        StringBuilder svg = new StringBuilder(32_768);
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(n(p.widthMm())).append("mm\" height=\"").append(n(p.heightMm()))
                .append("mm\" viewBox=\"0 0 ").append(n(p.widthMm())).append(' ')
                .append(n(p.heightMm())).append("\">\n")
                .append("  <metadata>Variable Line Grating; orientation=")
                .append(p.lineOrientation()).append("; progression=").append(p.progression())
                .append("; print at 100%; disable fit-to-page scaling.</metadata>\n")
                .append("  <rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n")
                .append("  <g fill=\"black\" shape-rendering=\"crispEdges\">\n");

        double band = p.showReferenceBands() ? p.referenceBandSizeMm() : 0.0;
        appendIntervals(svg, p, layout, VariableLineGratingModel.opaqueIntervals(p),
                band, false, false);
        if (p.showReferenceBands()) {
            List<VariableLineGratingModel.Interval> start =
                    VariableLineGratingModel.constantPitchOpaqueIntervals(
                            p, VariableLineGratingModel.pitchMmAtNormalized(p, 0.0));
            List<VariableLineGratingModel.Interval> end =
                    VariableLineGratingModel.constantPitchOpaqueIntervals(
                            p, VariableLineGratingModel.pitchMmAtNormalized(p, 1.0));
            appendIntervals(svg, p, layout, start, band, true, false);
            appendIntervals(svg, p, layout, end, band, false, true);
        }
        svg.append("  </g>\n");
        if (p.showAxis()) appendAxis(svg, p, layout);
        svg.append("</svg>\n");
        return svg.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendIntervals(
            StringBuilder svg,
            VariableLineGratingParameters p,
            VariableLineGratingModel.Layout layout,
            List<VariableLineGratingModel.Interval> intervals,
            double band,
            boolean startBand,
            boolean endBand) {
        for (VariableLineGratingModel.Interval interval : intervals) {
            double x;
            double y;
            double width;
            double height;
            if (p.lineOrientation() == LineOrientation.VERTICAL) {
                x = layout.activeXmm() + interval.startMm();
                width = interval.endMm() - interval.startMm();
                if (startBand) {
                    y = layout.activeYmm();
                    height = band;
                } else if (endBand) {
                    y = layout.activeYmm() + layout.activeHeightMm() - band;
                    height = band;
                } else {
                    y = layout.activeYmm() + band;
                    height = layout.activeHeightMm() - 2.0 * band;
                }
            } else {
                y = layout.activeYmm() + interval.startMm();
                height = interval.endMm() - interval.startMm();
                if (startBand) {
                    x = layout.activeXmm();
                    width = band;
                } else if (endBand) {
                    x = layout.activeXmm() + layout.activeWidthMm() - band;
                    width = band;
                } else {
                    x = layout.activeXmm() + band;
                    width = layout.activeWidthMm() - 2.0 * band;
                }
            }
            if (width <= 0.0 || height <= 0.0) continue;
            svg.append("    <rect x=\"").append(n(x)).append("\" y=\"").append(n(y))
                    .append("\" width=\"").append(n(width)).append("\" height=\"")
                    .append(n(height)).append("\"/>\n");
        }
    }

    private static void appendAxis(
            StringBuilder svg,
            VariableLineGratingParameters p,
            VariableLineGratingModel.Layout layout) {
        svg.append("  <g fill=\"black\" stroke=\"black\" stroke-width=\"0.15\" ")
                .append("font-family=\"monospace\" font-size=\"2.4\">\n");
        if (p.lineOrientation() == LineOrientation.VERTICAL) {
            double y = layout.axisCoordinateMm();
            svg.append("    <line x1=\"").append(n(layout.activeXmm())).append("\" y1=\"")
                    .append(n(y)).append("\" x2=\"")
                    .append(n(layout.activeXmm() + layout.activeWidthMm())).append("\" y2=\"")
                    .append(n(y)).append("\"/>\n");
            for (int i = 0; i < p.tickCount(); i++) {
                double u = i / (double) (p.tickCount() - 1);
                double x = layout.activeXmm() + u * layout.activeWidthMm();
                svg.append("    <line x1=\"").append(n(x)).append("\" y1=\"")
                        .append(n(y - 1.0)).append("\" x2=\"").append(n(x)).append("\" y2=\"")
                        .append(n(y + 1.0)).append("\"/>\n")
                        .append("    <text stroke=\"none\" text-anchor=\"middle\" x=\"")
                        .append(n(x)).append("\" y=\"").append(n(y + 3.5)).append("\">")
                        .append(label(p, u, p.dpi())).append("</text>\n");
            }
            svg.append("    <text stroke=\"none\" text-anchor=\"middle\" x=\"")
                    .append(n(layout.activeXmm() + layout.activeWidthMm() / 2.0))
                    .append("\" y=\"").append(n(p.heightMm() - p.marginMm() * 0.45))
                    .append("\">VERTICAL LINES · PAGE X · PRINT 100%</text>\n");
        } else {
            double x = layout.axisCoordinateMm();
            svg.append("    <line x1=\"").append(n(x)).append("\" y1=\"")
                    .append(n(layout.activeYmm())).append("\" x2=\"").append(n(x))
                    .append("\" y2=\"").append(n(layout.activeYmm() + layout.activeHeightMm()))
                    .append("\"/>\n");
            for (int i = 0; i < p.tickCount(); i++) {
                double u = i / (double) (p.tickCount() - 1);
                double y = layout.activeYmm() + u * layout.activeHeightMm();
                svg.append("    <line x1=\"").append(n(x - 1.0)).append("\" y1=\"")
                        .append(n(y)).append("\" x2=\"").append(n(x + 1.0)).append("\" y2=\"")
                        .append(n(y)).append("\"/>\n")
                        .append("    <text stroke=\"none\" x=\"").append(n(x + 1.8))
                        .append("\" y=\"").append(n(y + 0.8)).append("\">")
                        .append(label(p, u, p.dpi())).append("</text>\n");
            }
            double titleX = p.widthMm() - p.marginMm() * 0.45;
            double titleY = layout.activeYmm() + layout.activeHeightMm() / 2.0;
            svg.append("    <text stroke=\"none\" text-anchor=\"middle\" transform=\"rotate(90 ")
                    .append(n(titleX)).append(' ').append(n(titleY)).append(")\" x=\"")
                    .append(n(titleX)).append("\" y=\"").append(n(titleY))
                    .append("\">HORIZONTAL LINES · PAGE Y · PRINT 100%</text>\n");
        }
        svg.append("  </g>\n");
    }

    private static String label(VariableLineGratingParameters p, double u, double dpi) {
        double pitchMm = VariableLineGratingModel.pitchMmAtNormalized(p, u);
        return switch (p.axisQuantity()) {
            case PITCH_UM -> compact(pitchMm * 1000.0) + " µm";
            case LINES_PER_MM -> compact(1.0 / pitchMm) + " lines/mm";
            case DEVICE_DOTS_PER_PERIOD -> compact(pitchMm * dpi / Units.INCH_MM) + " dots/period";
        };
    }

    private static String compact(double value) {
        if (Math.abs(value) >= 100.0 || Math.abs(value - Math.rint(value)) < 0.05) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String n(double value) {
        String s = String.format(Locale.ROOT, "%.6f", value);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return end == 0 ? "0" : s.substring(0, end);
    }
}
