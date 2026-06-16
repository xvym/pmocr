package io.github.xvym.pmocr.translation;

import org.apache.commons.lang3.StringUtils;

import java.util.*;

public final class TranslationIndex {
    private final Map<String, String> exact = new HashMap<>();
    private final Map<String, String> nouns = new HashMap<>();
    private final Map<Character, List<TranslationTemplate>> templates = new HashMap<>();
    private final List<TranslationTemplate> wildcardTemplates = new ArrayList<>();
    private final Set<String> templateKeys = new HashSet<>();

    void addEntry(String japanese, String translation) {
        String source = TranslationTextUtils.normalize(japanese);
        String translated = TranslationTextUtils.normalize(translation);
        if (TranslationTextUtils.isUsefulText(source) || translated.isEmpty() || "日文".equals(source) || "翻译".equals(translated)) {
            return;
        }
        if (TranslationTextUtils.hasPlaceholder(source)) {
            if (TranslationTextUtils.fixedLiteral(source).isEmpty()) {
                return;
            }
            addTemplate(source, translated);
        } else {
            String normalizeKey = TranslationTextUtils.normalize(source);
            String compactKey = TranslationTextUtils.compact(source);

            if (StringUtils.isNotEmpty(normalizeKey)) {
                exact.putIfAbsent(normalizeKey, translated);
            }

            if (StringUtils.isNotEmpty(compactKey)) {
                exact.putIfAbsent(compactKey, translated);
            }
        }
    }

    void addNoun(String japanese, String translation) {
        String source = TranslationTextUtils.normalize(japanese);
        String translated = TranslationTextUtils.normalize(translation);
        if (TranslationTextUtils.isUsefulText(source) || translated.isEmpty()
                || "日文".equals(source) || "翻译".equals(translated)) {
            return;
        }
        String normalizeKey = TranslationTextUtils.normalize(source);
        String compactKey = TranslationTextUtils.compact(source);

        if (StringUtils.isNotEmpty(normalizeKey)) {
            nouns.putIfAbsent(normalizeKey, translated);
        }

        if (StringUtils.isNotEmpty(compactKey)) {
            nouns.putIfAbsent(compactKey, translated);
        }
    }

    public String translate(String japaneseText) {
        String normalized = TranslationTextUtils.normalize(japaneseText);
        String direct = exact.get(TranslationTextUtils.normalize(normalized));
        if (direct != null) {
            return direct;
        }
        direct = exact.get(TranslationTextUtils.compact(normalized));
        if (direct != null) {
            return direct;
        }

        char first = firstNonWhitespace(normalized);
        List<TranslationTemplate> candidates = templates.get(first);
        String matched = matchTemplates(candidates, normalized);
        if (matched != null) {
            return matched;
        }
        return matchTemplates(wildcardTemplates, normalized);
    }

    public void prepare() {
        Comparator<TranslationTemplate> comparator = (first, second) -> {
            int fixed = second.fixedLength - first.fixedLength;
            if (fixed != 0) {
                return fixed;
            }
            return second.translationFixedLength - first.translationFixedLength;
        };
        wildcardTemplates.sort(comparator);
        for (List<TranslationTemplate> entries : templates.values()) {
            entries.sort(comparator);
        }
    }

    String translateCapturedValue(String value) {
        String translated = nouns.get(TranslationTextUtils.normalize(value));
        if (translated != null) {
            return translated;
        }
        translated = nouns.get(TranslationTextUtils.compact(value));
        return translated == null ? TranslationTextUtils.normalize(value) : translated;
    }

    private void addTemplate(String japanese, String translation) {
        String key = japanese + "\u0000" + translation;
        if (!templateKeys.add(key)) {
            return;
        }
        TranslationTemplate entry = TranslationTemplate.compile(japanese, translation, this);
        if (entry.startsWithPlaceholder) {
            wildcardTemplates.add(entry);
        } else {
            List<TranslationTemplate> bucket = templates.computeIfAbsent(entry.firstLiteral, k -> new ArrayList<>());
            bucket.add(entry);
        }
    }

    private String matchTemplates(List<TranslationTemplate> entries, String japaneseText) {
        if (entries == null) {
            return null;
        }
        for (TranslationTemplate entry : entries) {
            String translated = entry.tryTranslate(japaneseText);
            if (translated != null) {
                return translated;
            }
        }
        return null;
    }

    private static char firstNonWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isWhitespace(character)) {
                return character;
            }
        }
        return 0;
    }
}
