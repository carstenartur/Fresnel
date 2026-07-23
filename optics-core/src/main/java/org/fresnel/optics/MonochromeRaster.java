package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Arrays;

/** Packed one-bit raster with one sample per addressed device dot. */
public record MonochromeRaster(
        int widthDots,
        int heightDots,
        int rowBytes,
        byte[] data,
        double dpiPageX,
        double dpiPageY
) {
    public MonochromeRaster {
        if (widthDots <= 0 || heightDots <= 0 || rowBytes != (widthDots + 7) / 8) {
            throw new IllegalArgumentException("invalid monochrome raster dimensions");
        }
        if (data == null || data.length != rowBytes * heightDots) {
            throw new IllegalArgumentException("invalid monochrome raster byte count");
        }
        if (!Double.isFinite(dpiPageX) || dpiPageX <= 0.0
                || !Double.isFinite(dpiPageY) || dpiPageY <= 0.0) {
            throw new IllegalArgumentException("page-axis DPI must be finite and positive");
        }
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    byte[] unsafeData() {
        return data;
    }

    public boolean isBlack(int x, int y) {
        if (x < 0 || x >= widthDots || y < 0 || y >= heightDots) {
            throw new IndexOutOfBoundsException("dot outside raster");
        }
        int value = data[y * rowBytes + (x >>> 3)] & 0xff;
        return (value & (0x80 >>> (x & 7))) != 0;
    }

    public byte[] row(int y) {
        if (y < 0 || y >= heightDots) throw new IndexOutOfBoundsException("row outside raster");
        return Arrays.copyOfRange(data, y * rowBytes, (y + 1) * rowBytes);
    }

    public BufferedImage toBufferedImage() {
        BufferedImage image = new BufferedImage(widthDots, heightDots, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster target = image.getRaster();
        int[] row = new int[widthDots];
        for (int y = 0; y < heightDots; y++) {
            for (int x = 0; x < widthDots; x++) row[x] = isBlack(x, y) ? 0 : 255;
            target.setSamples(0, y, widthDots, 1, 0, row);
        }
        return image;
    }
}
