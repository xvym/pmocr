package io.github.xvym.pmocr.ocr;

import java.awt.image.BufferedImage;

final class OcrImageSampler {
    private OcrImageSampler() {
    }

    static int calculateThreshold(BufferedImage image, TextBox box) {
        int min = 255;
        int max = 0;
        int left = clamp((int) Math.floor(box.textX), 0, image.getWidth() - 1);
        int right = clamp((int) Math.ceil(box.textX + OcrLayout.COLUMNS * OcrLayout.TILE_SIZE * box.scale),
                left + 1, image.getWidth());
        int top = clamp((int) Math.floor(box.firstLineY - OcrLayout.MARK_OFFSET * box.scale),
                0, image.getHeight() - 1);
        int bottom = clamp((int) Math.ceil(box.firstLineY + 24.0 * box.scale), top + 1, image.getHeight());
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int value = luminance(image.getRGB(x, y));
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        return clamp((min + max) / 2, 70, 210);
    }

    static byte[] sampleRows(BufferedImage image, double x, double y, double scale, int rows,
                             int threshold) {
        byte[] result = new byte[rows];
        for (int row = 0; row < rows; row++) {
            int bits = 0;
            for (int column = 0; column < OcrLayout.TILE_SIZE; column++) {
                int sampleX = clamp((int) Math.floor(x + (column + 0.5) * scale),
                        0, image.getWidth() - 1);
                int sampleY = clamp((int) Math.floor(y + (row + 0.5) * scale),
                        0, image.getHeight() - 1);
                if (luminance(image.getRGB(sampleX, sampleY)) < threshold) {
                    bits |= 0x80 >> column;
                }
            }
            result[row] = (byte) bits;
        }
        return result;
    }

    static long fingerprint(BufferedImage image, TextBox box, int threshold) {
        long hash = 0xcbf29ce484222325L;
        for (int line = 0; line < OcrLayout.LINES; line++) {
            double y = box.firstLineY + line * OcrLayout.LINE_HEIGHT * box.scale;
            for (int column = 0; column < OcrLayout.COLUMNS; column++) {
                double x = box.textX + column * OcrLayout.TILE_SIZE * box.scale;
                byte[] mark = sampleRows(image, x, y - OcrLayout.MARK_OFFSET * box.scale, box.scale, 3, threshold);
                byte[] tile = sampleRows(image, x, y, box.scale, OcrLayout.TILE_SIZE, threshold);
                for (byte value : mark) {
                    hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
                }
                for (byte value : tile) {
                    hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    static boolean isBlank(byte[] rows) {
        for (byte row : rows) {
            if (row != 0) {
                return false;
            }
        }
        return true;
    }

    static int luminance(int rgb) {
        int red = (rgb >>> 16) & 0xff;
        int green = (rgb >>> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
