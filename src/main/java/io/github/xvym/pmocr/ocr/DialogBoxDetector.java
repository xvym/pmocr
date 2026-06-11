package io.github.xvym.pmocr.ocr;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

final class DialogBoxDetector {
    TextBox detect(BufferedImage image) {
        List<DarkRun> runs = new ArrayList<DarkRun>();
        int minimumLength = Math.max(45, image.getWidth() / 3);
        for (int y = 0; y < image.getHeight(); y++) {
            DarkRun run = longestDarkRun(image, y, 135);
            if (run.length() >= minimumLength) {
                runs.add(run);
            }
        }

        TextBox best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (DarkRun top : runs) {
            double scale = top.length() / OcrLayout.TEXT_BOX_LINE_LENGTH;
            if (scale < 0.65 || scale > 12.0) {
                continue;
            }
            for (DarkRun bottom : runs) {
                if (bottom.y <= top.y) {
                    continue;
                }
                double logicalGap = (bottom.y - top.y) / scale;
                if (logicalGap < 40.5 || logicalGap > 44.5) {
                    continue;
                }
                double lengthRatio = bottom.length() / (double) top.length();
                double centerGap = Math.abs(bottom.center() - top.center()) / scale;
                // Some emulator overlays cover the lower-right corner of the box.
                if (lengthRatio < 0.88 || lengthRatio > 1.08 || centerGap > 8.0) {
                    continue;
                }

                int left = OcrImageSampler.clamp((int) Math.round(top.start - 2.0 * scale),
                        0, image.getWidth() - 1);
                int topY = OcrImageSampler.clamp(top.y, 0, image.getHeight() - 1);
                int right = OcrImageSampler.clamp((int) Math.round(top.end + 2.0 * scale),
                        left, image.getWidth() - 1);
                int bottomY = OcrImageSampler.clamp(bottom.y + Math.max(1, (int) Math.round(scale)),
                        topY, image.getHeight());
                Rectangle bounds = new Rectangle(left, topY, right - left + 1, bottomY - topY);
                TextBox candidate = new TextBox(bounds, scale,
                        top.start + OcrLayout.TEXT_LEFT_OFFSET * scale,
                        top.y + OcrLayout.FIRST_LINE_OFFSET * scale);

                if (!candidate.hasRoom(image)) {
                    continue;
                }
                double score = top.length() * logicalGap
                        - Math.abs(logicalGap - 42.0) * 100.0
                        - Math.abs(lengthRatio - 1.0) * 300.0
                        - centerGap * 50.0
                        + top.y * 0.01;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static DarkRun longestDarkRun(BufferedImage image, int y, int threshold) {
        int bestStart = 0;
        int bestEnd = -1;
        int currentStart = -1;
        for (int x = 0; x < image.getWidth(); x++) {
            boolean dark = OcrImageSampler.luminance(image.getRGB(x, y)) < threshold;
            if (dark && currentStart < 0) {
                currentStart = x;
            }
            if ((!dark || x == image.getWidth() - 1) && currentStart >= 0) {
                int end = dark && x == image.getWidth() - 1 ? x : x - 1;
                if (end - currentStart > bestEnd - bestStart) {
                    bestStart = currentStart;
                    bestEnd = end;
                }
                currentStart = -1;
            }
        }
        return new DarkRun(y, bestStart, bestEnd);
    }

    private static final class DarkRun {
        final int y;
        final int start;
        final int end;

        DarkRun(int y, int start, int end) {
            this.y = y;
            this.start = start;
            this.end = end;
        }

        int length() {
            return end >= start ? end - start + 1 : 0;
        }

        double center() {
            return (start + end) / 2.0;
        }
    }
}
