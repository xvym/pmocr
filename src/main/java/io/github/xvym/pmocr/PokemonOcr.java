package io.github.xvym.pmocr;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public final class PokemonOcr {
    private static final int COLUMNS = 18;
    private static final int LINES = 2;
    private static final int MAX_CHARACTER_DISTANCE = 10;
    private static final int MAX_MARK_DISTANCE = 2;

    private final FontTemplates templates = new FontTemplates();

    public Probe probe(BufferedImage image) {
        TextBox box = detectTextBox(image);
        if (box == null) {
            return Probe.notFound();
        }
        int threshold = calculateThreshold(image, box);
        return new Probe(true, fingerprint(image, box, threshold), box.scale);
    }

    public Recognition recognize(BufferedImage image) {
        TextBox box = detectTextBox(image);
        if (box == null) {
            return Recognition.notFound();
        }

        int threshold = calculateThreshold(image, box);
        List<String> lines = new ArrayList<String>();
        int totalDistance = 0;
        int matchedCharacters = 0;
        int unknownCharacters = 0;

        for (int line = 0; line < LINES; line++) {
            Cell[] cells = new Cell[COLUMNS];
            for (int column = 0; column < COLUMNS; column++) {
                double x = box.textX + column * 8.0 * box.scale;
                double y = box.firstLineY + line * 16.0 * box.scale;
                byte[] tile = sampleRows(image, x, y, box.scale, 8, threshold);
                if (isBlank(tile)) {
                    cells[column] = Cell.space();
                    continue;
                }

                FontTemplates.Match match = templates.match(tile);
                if (match.distance > MAX_CHARACTER_DISTANCE) {
                    cells[column] = Cell.unknown();
                    unknownCharacters++;
                    continue;
                }

                byte[] markRows = sampleRows(image, x, y - 3.0 * box.scale, box.scale, 3, threshold);
                char value = compose(match.character, detectMark(markRows));
                cells[column] = Cell.character(displayCharacter(value));
                totalDistance += match.distance;
                matchedCharacters++;
            }
            lines.add(resolveSharedGlyphs(renderLine(cells)));
        }

        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        String text = join(lines);
        double averageDistance = matchedCharacters == 0 ? 0.0 : (double) totalDistance / matchedCharacters;
        return new Recognition(true, text, box.bounds, box.scale, averageDistance, unknownCharacters,
                fingerprint(image, box, threshold));
    }

    private TextBox detectTextBox(BufferedImage image) {
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
            double scale = top.length() / 151.0;
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

                int left = clamp((int) Math.round(top.start - 2.0 * scale), 0, image.getWidth() - 1);
                int topY = clamp(top.y, 0, image.getHeight() - 1);
                int right = clamp((int) Math.round(top.end + 2.0 * scale), left, image.getWidth() - 1);
                int bottomY = clamp(bottom.y + Math.max(1, (int) Math.round(scale)), topY, image.getHeight());
                Rectangle bounds = new Rectangle(left, topY, right - left + 1, bottomY - topY);
                TextBox candidate = new TextBox(bounds, scale, top.start + 4.0 * scale,
                        top.y + 14.0 * scale);

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
            boolean dark = luminance(image.getRGB(x, y)) < threshold;
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

    private static int calculateThreshold(BufferedImage image, TextBox box) {
        int min = 255;
        int max = 0;
        int left = clamp((int) Math.floor(box.textX), 0, image.getWidth() - 1);
        int right = clamp((int) Math.ceil(box.textX + COLUMNS * 8.0 * box.scale), left + 1, image.getWidth());
        int top = clamp((int) Math.floor(box.firstLineY - 3.0 * box.scale), 0, image.getHeight() - 1);
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

    private static byte[] sampleRows(BufferedImage image, double x, double y, double scale, int rows,
                                     int threshold) {
        byte[] result = new byte[rows];
        for (int row = 0; row < rows; row++) {
            int bits = 0;
            for (int column = 0; column < 8; column++) {
                int sampleX = clamp((int) Math.floor(x + (column + 0.5) * scale), 0, image.getWidth() - 1);
                int sampleY = clamp((int) Math.floor(y + (row + 0.5) * scale), 0, image.getHeight() - 1);
                if (luminance(image.getRGB(sampleX, sampleY)) < threshold) {
                    bits |= 0x80 >> column;
                }
            }
            result[row] = (byte) bits;
        }
        return result;
    }

    private char detectMark(byte[] rows) {
        if (isBlank(rows)) {
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

    private static char compose(char base, char mark) {
        if (mark == 0) {
            return base;
        }
        char combining = mark == '゛' ? '\u3099' : '\u309a';
        String normalized = Normalizer.normalize(new String(new char[]{base, combining}), Normalizer.Form.NFC);
        return normalized.length() == 1 ? normalized.charAt(0) : base;
    }

    private static char displayCharacter(char value) {
        return value == '!' ? '！' : value;
    }

    private static String renderLine(Cell[] cells) {
        int lastKnown = -1;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i].type == CellType.CHARACTER) {
                lastKnown = i;
            }
        }
        if (lastKnown < 0) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i <= lastKnown; i++) {
            if (cells[i].type == CellType.CHARACTER) {
                line.append(cells[i].value);
            } else if (cells[i].type == CellType.SPACE) {
                line.append(' ');
            } else {
                line.append('�');
            }
        }
        return line.toString();
    }

    private static String resolveSharedGlyphs(String line) {
        char[] characters = line.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            if (characters[i] != 'リ') {
                continue;
            }
            char previous = nearestNonSpace(characters, i, -1);
            char next = nearestNonSpace(characters, i, 1);
            boolean hiraganaContext = isHiragana(previous) || isHiragana(next);
            boolean katakanaContext = isKatakana(previous) || isKatakana(next);
            if (hiraganaContext && !katakanaContext) {
                characters[i] = 'り';
            }
        }
        return new String(characters);
    }

    private static char nearestNonSpace(char[] characters, int start, int direction) {
        for (int i = start + direction; i >= 0 && i < characters.length; i += direction) {
            if (!Character.isWhitespace(characters[i]) && characters[i] != '�') {
                return characters[i];
            }
        }
        return 0;
    }

    private static boolean isHiragana(char value) {
        return value >= '\u3040' && value <= '\u309f';
    }

    private static boolean isKatakana(char value) {
        return value >= '\u30a0' && value <= '\u30ff';
    }

    private static long fingerprint(BufferedImage image, TextBox box, int threshold) {
        long hash = 0xcbf29ce484222325L;
        for (int line = 0; line < LINES; line++) {
            double y = box.firstLineY + line * 16.0 * box.scale;
            for (int column = 0; column < COLUMNS; column++) {
                double x = box.textX + column * 8.0 * box.scale;
                byte[] mark = sampleRows(image, x, y - 3.0 * box.scale, box.scale, 3, threshold);
                byte[] tile = sampleRows(image, x, y, box.scale, 8, threshold);
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

    private static boolean isBlank(byte[] rows) {
        for (byte row : rows) {
            if (row != 0) {
                return false;
            }
        }
        return true;
    }

    private static int luminance(int rgb) {
        int red = (rgb >>> 16) & 0xff;
        int green = (rgb >>> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String join(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

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

    private static final class TextBox {
        final Rectangle bounds;
        final double scale;
        final double textX;
        final double firstLineY;

        TextBox(Rectangle bounds, double scale, double textX, double firstLineY) {
            this.bounds = bounds;
            this.scale = scale;
            this.textX = textX;
            this.firstLineY = firstLineY;
        }

        boolean hasRoom(BufferedImage image) {
            return textX >= 0 && firstLineY - 3.0 * scale >= 0
                    && textX + COLUMNS * 8.0 * scale <= image.getWidth() + scale
                    && firstLineY + 24.0 * scale <= image.getHeight() + scale;
        }
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

    private enum CellType {
        CHARACTER, SPACE, UNKNOWN
    }

    private static final class Cell {
        final CellType type;
        final char value;

        private Cell(CellType type, char value) {
            this.type = type;
            this.value = value;
        }

        static Cell character(char value) {
            return new Cell(CellType.CHARACTER, value);
        }

        static Cell space() {
            return new Cell(CellType.SPACE, ' ');
        }

        static Cell unknown() {
            return new Cell(CellType.UNKNOWN, '�');
        }
    }
}
