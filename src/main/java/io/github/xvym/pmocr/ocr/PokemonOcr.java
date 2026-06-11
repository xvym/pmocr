package io.github.xvym.pmocr.ocr;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 宝可梦金银对话框 OCR 编排器，负责串联对话框定位、像素采样和字体匹配。
 */
public final class PokemonOcr {
    private static final int MAX_CHARACTER_DISTANCE = 10;
    private static final int MAX_MARK_DISTANCE = 2;

    private final FontTemplates templates;
    private final DialogBoxDetector detector;

    public PokemonOcr() {
        this(new FontTemplates(), new DialogBoxDetector());
    }

    PokemonOcr(FontTemplates templates, DialogBoxDetector detector) {
        this.templates = templates;
        this.detector = detector;
    }

    /**
     * 轻量探测当前截图是否包含对话框，并生成文字区域指纹。
     */
    public Probe probe(BufferedImage image) {
        TextBox box = detector.detect(image);
        if (box == null) {
            return Probe.notFound();
        }
        int threshold = OcrImageSampler.calculateThreshold(image, box);
        return new Probe(true, OcrImageSampler.fingerprint(image, box, threshold), box.scale);
    }

    /**
     * 对截图执行完整 OCR，返回识别文本、文本框位置、缩放比例和匹配质量信息。
     */
    public Recognition recognize(BufferedImage image) {
        TextBox box = detector.detect(image);
        if (box == null) {
            return Recognition.notFound();
        }

        int threshold = OcrImageSampler.calculateThreshold(image, box);
        List<String> lines = new ArrayList<String>();
        int totalDistance = 0;
        int matchedCharacters = 0;
        int unknownCharacters = 0;

        for (int line = 0; line < OcrLayout.LINES; line++) {
            OcrTextBuilder.Cell[] cells = new OcrTextBuilder.Cell[OcrLayout.COLUMNS];
            for (int column = 0; column < OcrLayout.COLUMNS; column++) {
                double x = box.textX + column * OcrLayout.TILE_SIZE * box.scale;
                double y = box.firstLineY + line * OcrLayout.LINE_HEIGHT * box.scale;
                byte[] tile = OcrImageSampler.sampleRows(image, x, y, box.scale, OcrLayout.TILE_SIZE, threshold);
                if (OcrImageSampler.isBlank(tile)) {
                    cells[column] = OcrTextBuilder.Cell.space();
                    continue;
                }

                FontTemplates.Match match = templates.match(tile);
                if (match.distance > MAX_CHARACTER_DISTANCE) {
                    cells[column] = OcrTextBuilder.Cell.unknown();
                    unknownCharacters++;
                    continue;
                }

                byte[] markRows = OcrImageSampler.sampleRows(image, x,
                        y - OcrLayout.MARK_OFFSET * box.scale, box.scale, 3, threshold);
                char value = OcrTextBuilder.compose(match.character, detectMark(markRows));
                cells[column] = OcrTextBuilder.Cell.character(OcrTextBuilder.displayCharacter(value));
                totalDistance += match.distance;
                matchedCharacters++;
            }
            lines.add(OcrTextBuilder.resolveSharedGlyphs(OcrTextBuilder.renderLine(cells)));
        }

        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        String text = OcrTextBuilder.join(lines);
        double averageDistance = matchedCharacters == 0 ? 0.0 : (double) totalDistance / matchedCharacters;
        return new Recognition(true, text, box.bounds, box.scale, averageDistance, unknownCharacters,
                OcrImageSampler.fingerprint(image, box, threshold));
    }

    private char detectMark(byte[] rows) {
        if (OcrImageSampler.isBlank(rows)) {
            return 0;
        }
        int dakuten = templates.markDistance(rows, '゛');
        int handakuten = templates.markDistance(rows, '゜');
        int best = Math.min(dakuten, handakuten);
        if (best > MAX_MARK_DISTANCE) {
            return 0;
        }
        return dakuten <= handakuten ? '゛' : '゜';
    }

    /**
     * 完整 OCR 结果。
     */
    public static final class Recognition {
        private final boolean textBoxFound;
        private final String text;
        private final Rectangle textBox;
        private final double scale;
        private final double averageDistance;
        private final int unknownCharacters;
        private final long fingerprint;

        private Recognition(boolean textBoxFound, String text, Rectangle textBox, double scale,
                            double averageDistance, int unknownCharacters, long fingerprint) {
            this.textBoxFound = textBoxFound;
            this.text = text;
            this.textBox = textBox;
            this.scale = scale;
            this.averageDistance = averageDistance;
            this.unknownCharacters = unknownCharacters;
            this.fingerprint = fingerprint;
        }

        private static Recognition notFound() {
            return new Recognition(false, "", null, 0.0, 0.0, 0, 0L);
        }

        public boolean isTextBoxFound() {
            return textBoxFound;
        }

        public String getText() {
            return text;
        }

        public Rectangle getTextBox() {
            return textBox == null ? null : new Rectangle(textBox);
        }

        public double getScale() {
            return scale;
        }

        public double getAverageDistance() {
            return averageDistance;
        }

        public int getUnknownCharacters() {
            return unknownCharacters;
        }

        public long getFingerprint() {
            return fingerprint;
        }
    }

    /**
     * 稳定性探测结果。
     */
    public static final class Probe {
        private final boolean textBoxFound;
        private final long fingerprint;
        private final double scale;

        private Probe(boolean textBoxFound, long fingerprint, double scale) {
            this.textBoxFound = textBoxFound;
            this.fingerprint = fingerprint;
            this.scale = scale;
        }

        private static Probe notFound() {
            return new Probe(false, 0L, 0.0);
        }

        public boolean isTextBoxFound() {
            return textBoxFound;
        }

        public long getFingerprint() {
            return fingerprint;
        }

        public double getScale() {
            return scale;
        }
    }
}
