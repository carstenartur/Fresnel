package org.fresnel.optics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * STL export for height-map based phase-relief surfaces.
 */
public final class StlExporter {

    private StlExporter() {}

    public static byte[] toBinaryStl(double[][] heightMapMm, double pixelSizeMm) {
        Grid g = validate(heightMapMm, pixelSizeMm);
        int triCount = triangleCount(g.w, g.h);
        ByteBuffer out = ByteBuffer.allocate(84 + triCount * 50).order(ByteOrder.LITTLE_ENDIAN);

        byte[] headerText = "Fresnel phase relief STL".getBytes(StandardCharsets.US_ASCII);
        byte[] header = new byte[80];
        System.arraycopy(headerText, 0, header, 0, Math.min(header.length, headerText.length));
        out.put(header);
        out.putInt(triCount);

        // Top surface
        for (int y = 0; y < g.h - 1; y++) {
            for (int x = 0; x < g.w - 1; x++) {
                Vec3 p00 = top(g, x, y);
                Vec3 p10 = top(g, x + 1, y);
                Vec3 p11 = top(g, x + 1, y + 1);
                Vec3 p01 = top(g, x, y + 1);
                writeTriangle(out, p00, p10, p11);
                writeTriangle(out, p00, p11, p01);
            }
        }

        // Bottom surface (z=0), reversed winding.
        for (int y = 0; y < g.h - 1; y++) {
            for (int x = 0; x < g.w - 1; x++) {
                Vec3 b00 = bottom(g, x, y);
                Vec3 b10 = bottom(g, x + 1, y);
                Vec3 b11 = bottom(g, x + 1, y + 1);
                Vec3 b01 = bottom(g, x, y + 1);
                writeTriangle(out, b00, b01, b11);
                writeTriangle(out, b00, b11, b10);
            }
        }

        // Left boundary (x=0, outward -x)
        for (int y = 0; y < g.h - 1; y++) {
            Vec3 t0 = top(g, 0, y);
            Vec3 t1 = top(g, 0, y + 1);
            Vec3 b1 = bottom(g, 0, y + 1);
            Vec3 b0 = bottom(g, 0, y);
            writeTriangle(out, t0, t1, b1);
            writeTriangle(out, t0, b1, b0);
        }

        // Right boundary (x=max, outward +x)
        int xMax = g.w - 1;
        for (int y = 0; y < g.h - 1; y++) {
            Vec3 t0 = top(g, xMax, y);
            Vec3 b0 = bottom(g, xMax, y);
            Vec3 b1 = bottom(g, xMax, y + 1);
            Vec3 t1 = top(g, xMax, y + 1);
            writeTriangle(out, t0, b0, b1);
            writeTriangle(out, t0, b1, t1);
        }

        // Top boundary (y=0, outward -y)
        for (int x = 0; x < g.w - 1; x++) {
            Vec3 t0 = top(g, x, 0);
            Vec3 b0 = bottom(g, x, 0);
            Vec3 b1 = bottom(g, x + 1, 0);
            Vec3 t1 = top(g, x + 1, 0);
            writeTriangle(out, t0, b0, b1);
            writeTriangle(out, t0, b1, t1);
        }

        // Bottom boundary (y=max, outward +y)
        int yMax = g.h - 1;
        for (int x = 0; x < g.w - 1; x++) {
            Vec3 t0 = top(g, x, yMax);
            Vec3 t1 = top(g, x + 1, yMax);
            Vec3 b1 = bottom(g, x + 1, yMax);
            Vec3 b0 = bottom(g, x, yMax);
            writeTriangle(out, t0, t1, b1);
            writeTriangle(out, t0, b1, b0);
        }

        return out.array();
    }

    public static String toAsciiStl(double[][] heightMapMm, double pixelSizeMm, String solidName) {
        Grid g = validate(heightMapMm, pixelSizeMm);
        String name = (solidName == null || solidName.isBlank()) ? "phase_relief" : solidName.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("solid ").append(name).append('\n');
        byte[] binary = toBinaryStl(g.heightMap, g.pixelSizeMm);
        ByteBuffer bb = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(84);
        for (int i = 0; i < triangleCount(g.w, g.h); i++) {
            float nx = bb.getFloat();
            float ny = bb.getFloat();
            float nz = bb.getFloat();
            float ax = bb.getFloat();
            float ay = bb.getFloat();
            float az = bb.getFloat();
            float bx = bb.getFloat();
            float by = bb.getFloat();
            float bz = bb.getFloat();
            float cx = bb.getFloat();
            float cy = bb.getFloat();
            float cz = bb.getFloat();
            bb.getShort(); // attribute
            sb.append(String.format(Locale.ROOT, "  facet normal %.7g %.7g %.7g%n", nx, ny, nz));
            sb.append("    outer loop\n");
            sb.append(String.format(Locale.ROOT, "      vertex %.7g %.7g %.7g%n", ax, ay, az));
            sb.append(String.format(Locale.ROOT, "      vertex %.7g %.7g %.7g%n", bx, by, bz));
            sb.append(String.format(Locale.ROOT, "      vertex %.7g %.7g %.7g%n", cx, cy, cz));
            sb.append("    endloop\n");
            sb.append("  endfacet\n");
        }
        sb.append("endsolid ").append(name).append('\n');
        return sb.toString();
    }

    private static void writeTriangle(ByteBuffer out, Vec3 a, Vec3 b, Vec3 c) {
        Vec3 normal = normal(a, b, c);
        out.putFloat((float) normal.x);
        out.putFloat((float) normal.y);
        out.putFloat((float) normal.z);
        out.putFloat((float) a.x);
        out.putFloat((float) a.y);
        out.putFloat((float) a.z);
        out.putFloat((float) b.x);
        out.putFloat((float) b.y);
        out.putFloat((float) b.z);
        out.putFloat((float) c.x);
        out.putFloat((float) c.y);
        out.putFloat((float) c.z);
        out.putShort((short) 0);
    }

    private static Vec3 normal(Vec3 a, Vec3 b, Vec3 c) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double uz = b.z - a.z;
        double vx = c.x - a.x;
        double vy = c.y - a.y;
        double vz = c.z - a.z;
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double n = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (n <= 1e-30) return new Vec3(0, 0, 0);
        return new Vec3(nx / n, ny / n, nz / n);
    }

    private static int triangleCount(int w, int h) {
        return 4 * (w - 1) * (h - 1) + 4 * (w + h - 2);
    }

    private static Grid validate(double[][] heightMapMm, double pixelSizeMm) {
        if (heightMapMm == null || heightMapMm.length < 2)
            throw new IllegalArgumentException("heightMap must have at least 2 rows");
        int w = heightMapMm[0].length;
        if (w < 2) throw new IllegalArgumentException("heightMap must have at least 2 columns");
        for (double[] row : heightMapMm) {
            if (row == null || row.length != w)
                throw new IllegalArgumentException("heightMap must be rectangular");
            for (double z : row) {
                if (!Double.isFinite(z) || z < 0.0)
                    throw new IllegalArgumentException("heightMap values must be finite and >= 0");
            }
        }
        if (!(pixelSizeMm > 0.0) || !Double.isFinite(pixelSizeMm))
            throw new IllegalArgumentException("pixelSizeMm must be finite and > 0");
        return new Grid(heightMapMm, w, heightMapMm.length, pixelSizeMm);
    }

    private static Vec3 top(Grid g, int x, int y) {
        return new Vec3(x * g.pixelSizeMm, y * g.pixelSizeMm, g.heightMap[y][x]);
    }

    private static Vec3 bottom(Grid g, int x, int y) {
        return new Vec3(x * g.pixelSizeMm, y * g.pixelSizeMm, 0.0);
    }

    private record Vec3(double x, double y, double z) {}

    private record Grid(double[][] heightMap, int w, int h, double pixelSizeMm) {}
}

