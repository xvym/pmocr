package io.github.xvym.pmocr.translation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TranslationTemplate {
    final Pattern pattern;
    final String translationTemplate;
    final List<String> placeholders;
    final TranslationIndex index;
    final char firstLiteral;
    final boolean startsWithPlaceholder;
    final int fixedLength;
    final int translationFixedLength;

    private TranslationTemplate(Pattern pattern, String translationTemplate, List<String> placeholders,
                                TranslationIndex index, char firstLiteral, boolean startsWithPlaceholder,
                                int fixedLength, int translationFixedLength) {
        this.pattern = pattern;
        this.translationTemplate = translationTemplate;
        this.placeholders = placeholders;
        this.index = index;
        this.firstLiteral = firstLiteral;
        this.startsWithPlaceholder = startsWithPlaceholder;
        this.fixedLength = fixedLength;
        this.translationFixedLength = translationFixedLength;
    }

    static TranslationTemplate compile(String japaneseTemplate, String translationTemplate,
                                       TranslationIndex index) {
        String normalized = TranslationTextUtils.normalize(japaneseTemplate);
        Matcher matcher = TranslationTextUtils.PLACEHOLDER.matcher(normalized);
        StringBuilder regex = new StringBuilder();
        List<String> placeholders = new ArrayList<String>();
        regex.append("^\\s*");
        int indexInTemplate = 0;
        char firstLiteral = 0;
        boolean startsWithPlaceholder = false;
        int fixedLength = 0;
        while (matcher.find()) {
            if (matcher.start() == 0) {
                startsWithPlaceholder = true;
            }
            String literal = normalized.substring(indexInTemplate, matcher.start());
            fixedLength += literal.replace(" ", "").replace("\n", "").length();
            if (firstLiteral == 0) {
                firstLiteral = firstLiteral(literal);
            }
            appendFlexibleLiteral(regex, literal);
            placeholders.add(matcher.group());
            regex.append("(.+?)");
            indexInTemplate = matcher.end();
        }
        String tail = normalized.substring(indexInTemplate);
        fixedLength += tail.replace(" ", "").replace("\n", "").length();
        if (firstLiteral == 0) {
            firstLiteral = firstLiteral(tail);
        }
        appendFlexibleLiteral(regex, tail);
        regex.append("\\s*$");
        return new TranslationTemplate(Pattern.compile(regex.toString()),
                TranslationTextUtils.normalize(translationTemplate), placeholders, index, firstLiteral,
                startsWithPlaceholder || firstLiteral == 0, fixedLength, fixedTextLength(translationTemplate));
    }

    String tryTranslate(String japaneseText) {
        Matcher matcher = pattern.matcher(TranslationTextUtils.normalize(japaneseText));
        if (!matcher.matches()) {
            return null;
        }
        final Map<String, String> values = new HashMap<String, String>();
        for (int i = 0; i < placeholders.size(); i++) {
            String token = placeholders.get(i);
            if (!values.containsKey(token)) {
                values.put(token, index.translateCapturedValue(matcher.group(i + 1)));
            }
        }
        Matcher replacementMatcher = TranslationTextUtils.PLACEHOLDER.matcher(translationTemplate);
        StringBuffer result = new StringBuffer();
        while (replacementMatcher.find()) {
            String token = replacementMatcher.group();
            String value = values.get(token);
            replacementMatcher.appendReplacement(result,
                    Matcher.quoteReplacement(value == null ? token : value));
        }
        replacementMatcher.appendTail(result);
        return result.toString();
    }

    private static char firstLiteral(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isWhitespace(character)) {
                return character;
            }
        }
        return 0;
    }

    private static void appendFlexibleLiteral(StringBuilder regex, String literal) {
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < literal.length(); i++) {
            char character = literal.charAt(i);
            if (Character.isWhitespace(character)) {
                appendQuoted(regex, plain);
                if (character == '\n') {
                    regex.append("\\s*\\n\\s*");
                } else {
                    regex.append("\\s*");
                }
            } else {
                plain.append(character);
            }
        }
        appendQuoted(regex, plain);
    }

    private static void appendQuoted(StringBuilder regex, StringBuilder plain) {
        if (plain.length() > 0) {
            regex.append(Pattern.quote(plain.toString()));
            plain.setLength(0);
        }
    }

    private static int fixedTextLength(String template) {
        String normalized = TranslationTextUtils.normalize(template);
        Matcher matcher = TranslationTextUtils.PLACEHOLDER.matcher(normalized);
        String withoutPlaceholders = matcher.replaceAll("");
        return withoutPlaceholders.replace(" ", "").replace("\n", "").length();
    }
}
