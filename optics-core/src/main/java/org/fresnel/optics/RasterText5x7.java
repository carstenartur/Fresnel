package org.fresnel.optics;

/** Small built-in bitmap font so production rasters are independent of host fonts. */
final class RasterText5x7 {

    interface Target {
        void setBlack(int x, int y);
    }

    private RasterText5x7() {}

    static int textWidth(String text, int scale) {
        return text.isEmpty() ? 0 : (text.length() * 6 - 1) * scale;
    }

    static void draw(Target target, int x, int y, String text, int scale) {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            drawGlyph(target, cursor, y, Character.toUpperCase(text.charAt(i)), scale, false);
            cursor += 6 * scale;
        }
    }

    static void drawClockwise(Target target, int x, int y, String text, int scale) {
        int cursor = y;
        for (int i = 0; i < text.length(); i++) {
            drawGlyph(target, x, cursor, Character.toUpperCase(text.charAt(i)), scale, true);
            cursor += 6 * scale;
        }
    }

    private static void drawGlyph(Target target, int x, int y, char c, int scale, boolean clockwise) {
        int[] rows = glyph(c);
        for (int row = 0; row < 7; row++) {
            int bits = rows[row];
            for (int col = 0; col < 5; col++) {
                if ((bits & (1 << (4 - col))) == 0) continue;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        if (clockwise) {
                            target.setBlack(x + (6 - row) * scale + dx, y + col * scale + dy);
                        } else {
                            target.setBlack(x + col * scale + dx, y + row * scale + dy);
                        }
                    }
                }
            }
        }
    }

    private static int[] glyph(char c) {
        return switch (c) {
            case 'A' -> r(14,17,17,31,17,17,17); case 'B' -> r(30,17,17,30,17,17,30);
            case 'C' -> r(14,17,16,16,16,17,14); case 'D' -> r(30,17,17,17,17,17,30);
            case 'E' -> r(31,16,16,30,16,16,31); case 'F' -> r(31,16,16,30,16,16,16);
            case 'G' -> r(14,17,16,23,17,17,15); case 'H' -> r(17,17,17,31,17,17,17);
            case 'I' -> r(31,4,4,4,4,4,31); case 'J' -> r(7,2,2,2,18,18,12);
            case 'K' -> r(17,18,20,24,20,18,17); case 'L' -> r(16,16,16,16,16,16,31);
            case 'M' -> r(17,27,21,21,17,17,17); case 'N' -> r(17,25,21,19,17,17,17);
            case 'O' -> r(14,17,17,17,17,17,14); case 'P' -> r(30,17,17,30,16,16,16);
            case 'Q' -> r(14,17,17,17,21,18,13); case 'R' -> r(30,17,17,30,20,18,17);
            case 'S' -> r(15,16,16,14,1,1,30); case 'T' -> r(31,4,4,4,4,4,4);
            case 'U' -> r(17,17,17,17,17,17,14); case 'V' -> r(17,17,17,17,17,10,4);
            case 'W' -> r(17,17,17,21,21,21,10); case 'X' -> r(17,17,10,4,10,17,17);
            case 'Y' -> r(17,17,10,4,4,4,4); case 'Z' -> r(31,1,2,4,8,16,31);
            case '0' -> r(14,17,19,21,25,17,14); case '1' -> r(4,12,4,4,4,4,14);
            case '2' -> r(14,17,1,2,4,8,31); case '3' -> r(30,1,1,14,1,1,30);
            case '4' -> r(2,6,10,18,31,2,2); case '5' -> r(31,16,16,30,1,1,30);
            case '6' -> r(14,16,16,30,17,17,14); case '7' -> r(31,1,2,4,8,8,8);
            case '8' -> r(14,17,17,14,17,17,14); case '9' -> r(14,17,17,15,1,1,14);
            case '-' -> r(0,0,0,31,0,0,0); case '.' -> r(0,0,0,0,0,12,12);
            case '/' -> r(1,2,2,4,8,8,16); case ':' -> r(0,12,12,0,12,12,0);
            case '%' -> r(17,2,4,8,17,0,0); case '+' -> r(0,4,4,31,4,4,0);
            default -> r(0,0,0,0,0,0,0);
        };
    }

    private static int[] r(int... rows) {
        return rows;
    }
}
