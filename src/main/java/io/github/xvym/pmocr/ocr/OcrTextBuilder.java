package io.github.xvym.pmocr.ocr;

import java.text.Normalizer;
import java.util.List;

final class OcrTextBuilder {
    private OcrTextBuilder() {
    }

    static char compose(char base, char mark) {
        if (mark == 0) {
            return base;
        }
        char combining = mark == '゛' ? '\u3099' : '\u309a';
        String normalized = Normalizer.normalize(new String(new char[]{base, combining}), Normalizer.Form.NFC);
        return normalized.length() == 1 ? normalized.charAt(0) : base;
    }

    static char displayCharacter(char value) {
        return value == '!' ? '！' : value;
    }

    static String renderLine(Cell[] cells) {
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

    static String resolveSharedGlyphs(String line) {
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

    static String join(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
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

    enum CellType {
        CHARACTER, SPACE, UNKNOWN
    }

    static final class Cell {
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
