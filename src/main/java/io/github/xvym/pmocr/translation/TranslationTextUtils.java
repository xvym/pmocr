package io.github.xvym.pmocr.translation;

import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class TranslationTextUtils {
    static final Pattern PLACEHOLDER = Pattern.compile("<[^>]+>|【[^】]+】");

    private TranslationTextUtils() {
    }

    /**
     * 规范化字符串，由于对换行特殊处理，不能使用StringUtils来替代
     *
     * @param value
     * @return
     */
    public static String normalize(String value) {
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
            if (StringUtils.isEmpty(line)) {
                continue;
            }
            result.append(StringUtils.trim(line));
        }
        return trim(result.toString());
    }

    public static String compact(String value) {
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

    public static String trim(String value) {
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

    public static boolean hasPlaceholder(String text) {
        return PLACEHOLDER.matcher(text).find();
    }

    public static String fixedLiteral(String text) {
        String normalized = normalize(text);
        String withoutPlaceholders = PLACEHOLDER.matcher(normalized).replaceAll("");
        return compact(withoutPlaceholders);
    }

    public static boolean isUsefulText(String value) {
        String text = trim(value);
        return text.isEmpty()
                || text.startsWith("|---")
                || text.startsWith("--图鉴")
                || text.startsWith("--２页")
                || text.startsWith("--开始");
    }
}
