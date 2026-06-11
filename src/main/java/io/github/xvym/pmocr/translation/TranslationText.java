package io.github.xvym.pmocr.translation;

import java.text.Normalizer;
import java.util.Collection;
import java.util.regex.Pattern;

final class TranslationText {
    static final Pattern PLACEHOLDER = Pattern.compile("<[^>]+>|【[^】]+】");

    private TranslationText() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u3000', ' ');
        String[] lines = normalized.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(trim(line));
        }
        return trim(result.toString());
    }

    static String normalizeKey(String value) {
        return normalize(value);
    }

    static String compactKey(String value) {
        String normalized = normalize(value);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (!Character.isWhitespace(character)) {
                result.append(character);
            }
        }
        return result.toString();
    }

    static String join(Collection<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(normalized);
        }
        return result.toString();
    }

    static String trim(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    static String firstNonEmpty(String first, String second) {
        return trim(first).isEmpty() ? second : first;
    }

    static boolean hasPlaceholder(String text) {
        return PLACEHOLDER.matcher(text).find();
    }

    static boolean isUsefulText(String value) {
        String text = trim(value);
        return !text.isEmpty()
                && !text.startsWith("|---")
                && !text.startsWith("--图鉴")
                && !text.startsWith("--２页")
                && !text.startsWith("--开始");
    }
}
