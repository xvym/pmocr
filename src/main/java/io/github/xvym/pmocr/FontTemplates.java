package io.github.xvym.pmocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class FontTemplates {
    private static final String CHARACTERS =
            "!0123456789×▶▷▼♀♂。" +
            "あいうえおかきくけこさしすせそたちっつてとなにぬねのはひふへほまみむめもゃやゅゆょよらるれろわをん゛゜" +
            "ァアィイゥウェエォオカキクケコサシスセソタチッツテトナニヌネノハヒフホマミムメモャヤュユョヨラリルレロワヲンー円．／？…「」『』";

    private final byte[][] tiles;

    FontTemplates() {
        if (CHARACTERS.length() != 133) {
            throw new IllegalStateException("字体字符映射不是 133 个字符");
        }
        byte[] data = loadResource("/pokemon_gs_font_1bpp.bin");
        if (data.length != CHARACTERS.length() * 8) {
            throw new IllegalStateException("字体矩阵应为 " + (CHARACTERS.length() * 8)
                    + " 字节，实际为 " + data.length);
        }
        tiles = new byte[CHARACTERS.length()][8];
        for (int i = 0; i < tiles.length; i++) {
            System.arraycopy(data, i * 8, tiles[i], 0, 8);
        }
    }

    Match match(byte[] tile) {
        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.length; i++) {
            int distance = distance(tile, tiles[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
                if (distance == 0) {
                    break;
                }
            }
        }
        return new Match(CHARACTERS.charAt(bestIndex), bestDistance);
    }

    int markDistance(byte[] threeRows, char mark) {
        int index = CHARACTERS.indexOf(mark);
        int distance = 0;
        for (int row = 0; row < 3; row++) {
            distance += Integer.bitCount((threeRows[row] ^ tiles[index][row + 5]) & 0xff);
        }
        return distance;
    }

    private static int distance(byte[] first, byte[] second) {
        int distance = 0;
        for (int row = 0; row < 8; row++) {
            distance += Integer.bitCount((first[row] ^ second[row]) & 0xff);
        }
        return distance;
    }

    private static byte[] loadResource(String name) {
        try (InputStream input = FontTemplates.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("找不到字体矩阵资源: " + name);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("读取字体矩阵失败", e);
        }
    }

    static final class Match {
        final char character;
        final int distance;

        Match(char character, int distance) {
            this.character = character;
            this.distance = distance;
        }
    }
}
