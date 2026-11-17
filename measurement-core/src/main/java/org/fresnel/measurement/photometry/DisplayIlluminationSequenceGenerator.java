package org.fresnel.measurement.photometry;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Deterministic binary disc patterns and compact-object, diffuse-area-light approximation. */
public final class DisplayIlluminationSequenceGenerator {
    public static final String VERSION = "display-photometric-stereo/1";
    public record Vector(double x, double y, double z) {}
    public enum Role { DARK, FLAT, DIRECTIONAL_LIGHT }
    public record Pattern(String id, Role role, double screenXmm, double screenYmm,
                          double diameterMm, Vector direction, double relativeIntensity, String pixelSha256) {}
    public record Manifest(String algorithmVersion, String sequenceId, String coordinateSystem,
                           DisplayGeometry geometry, double conditionNumber, List<Pattern> patterns) {}
    private DisplayIlluminationSequenceGenerator() {}

    public static Manifest generate(DisplayGeometry geometry) {
        var patterns = new ArrayList<Pattern>();
        for (int i = -2; i < geometry.lightCount(); i++) {
            Pattern p = pattern(geometry, i);
            String hash = pixelHash(geometry, raster(geometry, p));
            patterns.add(new Pattern(p.id(), p.role(), p.screenXmm(), p.screenYmm(), p.diameterMm(),
                    p.direction(), p.relativeIntensity(), hash));
        }
        // Record geometry and algorithm version as well as pixel identities: changing camera position
        // changes the inverse problem even if every projected raster is the same.
        var identity = digest();
        identity.update((VERSION + "\n" + geometry + "\n").getBytes(StandardCharsets.UTF_8));
        for (var p : patterns) identity.update(p.pixelSha256().getBytes(StandardCharsets.US_ASCII));
        return new Manifest(VERSION, HexFormat.of().formatHex(identity.digest()),
                "Screen centers: right/up as seen facing the monitor. Normals: camera image right/up, +Z toward camera.",
                geometry, conditionNumber(geometry.screenWidthMm(), geometry.screenHeightMm(),
                geometry.objectDistanceMm(), geometry.lightCount()), List.copyOf(patterns));
    }

    public static byte[] png(DisplayGeometry geometry, String patternId) {
        for (int i = -2; i < geometry.lightCount(); i++) {
            var p = pattern(geometry, i);
            if (!p.id().equals(patternId)) continue;
            var image = new BufferedImage(geometry.widthPx(), geometry.heightPx(), BufferedImage.TYPE_BYTE_GRAY);
            byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            System.arraycopy(raster(geometry, p), 0, target, 0, target.length);
            try (var out = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new IllegalArgumentException("Unknown illumination pattern: " + patternId);
    }

    private static Pattern pattern(DisplayGeometry g, int index) {
        if (index < 0) return new Pattern(index == -2 ? "dark" : "flat", index == -2 ? Role.DARK : Role.FLAT,
                0, 0, 0, null, index == -2 ? 0 : 1, null);
        double angle = index * 2 * Math.PI / g.lightCount();
        double x = .4 * g.screenWidthMm() * StrictMath.cos(angle);
        double y = .4 * g.screenHeightMm() * StrictMath.sin(angle);
        double d = g.objectDistanceMm();
        Vector normal = unit(-g.cameraOffsetXmm(), g.cameraOffsetYmm(), d);
        Vector right = unit(normal.z(), 0, -normal.x());
        Vector up = new Vector(normal.y()*right.z(), normal.z()*right.x() - normal.x()*right.z(), -normal.y()*right.x());
        Vector light = unit(x, y, d);
        var direction = new Vector(dot(light, right), dot(light, up), dot(light, normal));
        // Relative to the same patch at screen center: emitter cosine times inverse square distance.
        return new Pattern(String.format(Locale.ROOT, "light-%02d", index + 1), Role.DIRECTIONAL_LIGHT,
                -x, y, Math.min(g.screenWidthMm(), g.screenHeightMm()) * g.patchDiameterPercent() / 100,
                direction, light.z()*light.z()*light.z(), null);
    }

    /** The symmetric ring has diagonal L^T L in screen coordinates. Camera rotation preserves singular values. */
    static double conditionNumber(double width, double height, double distance, int count) {
        double xx = 0, yy = 0, zz = 0;
        for (int i = 0; i < count; i++) {
            double angle = i * 2 * Math.PI / count;
            var l = unit(.4*width*StrictMath.cos(angle), .4*height*StrictMath.sin(angle), distance);
            double w = StrictMath.pow(l.z(), 6);
            xx += w*l.x()*l.x();
            yy += w*l.y()*l.y();
            zz += w*l.z()*l.z();
        }
        return StrictMath.sqrt(Math.max(xx, Math.max(yy, zz)) / Math.min(xx, Math.min(yy, zz)));
    }

    private static Vector unit(double x, double y, double z) {
        double length = StrictMath.sqrt(x*x + y*y + z*z);
        return new Vector(x/length, y/length, z/length);
    }
    private static double dot(Vector a, Vector b) { return a.x()*b.x() + a.y()*b.y() + a.z()*b.z(); }
    private static String pixelHash(DisplayGeometry g, byte[] gray) {
        var hash = digest();
        hash.update("FRESNEL-PATTERN-RGB8-V1\0".getBytes(StandardCharsets.US_ASCII));
        hash.update(java.nio.ByteBuffer.allocate(8).putInt(g.widthPx()).putInt(g.heightPx()).array());
        byte[] row = new byte[3*g.widthPx()];
        for (int y = 0; y < g.heightPx(); y++) {
            for (int x = 0; x < g.widthPx(); x++) {
                byte value = gray[y*g.widthPx()+x];
                row[3*x] = value;
                row[3*x+1] = value;
                row[3*x+2] = value;
            }
            hash.update(row);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static byte[] raster(DisplayGeometry g, Pattern p) {
        int width = g.widthPx(), height = g.heightPx();
        byte[] pixels = new byte[width * height];
        if (p.role() == Role.FLAT) Arrays.fill(pixels, (byte) 255);
        if (p.role() != Role.DIRECTIONAL_LIGHT) return pixels;
        double radius = p.diameterMm() / 2;
        double cx = (.5 + p.screenXmm()/g.screenWidthMm())*width - .5;
        for (int y = 0; y < height; y++) {
            double dy = (.5 - (y + .5)/height)*g.screenHeightMm() - p.screenYmm();
            if (Math.abs(dy) > radius) continue;
            double halfWidth = StrictMath.sqrt(radius*radius - dy*dy) * width / g.screenWidthMm();
            int start = Math.max(0, (int) Math.ceil(cx - halfWidth));
            int end = Math.min(width, (int) Math.floor(cx + halfWidth) + 1);
            Arrays.fill(pixels, y*width + start, y*width + end, (byte) 255);
        }
        return pixels;
    }
}
