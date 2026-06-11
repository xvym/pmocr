package io.github.xvym.pmocr.translation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TranslationIndex {
    private final Map<String, String> exact = new HashMap<String, String>();
    private final Map<String, String> nouns = new HashMap<String, String>();
    private final Map<Character, List<TranslationTemplate>> templates =
            new HashMap<Character, List<TranslationTemplate>>();
    private final List<TranslationTemplate> wildcardTemplates = new ArrayList<TranslationTemplate>();
    private final Set<String> templateKeys = new HashSet<String>();

    void addEntry(String japanese, String translation) {
        String source = TranslationText.normalize(japanese);
        String translated = TranslationText.normalize(translation);
        if (!TranslationText.isUsefulText(source) || translated.isEmpty()
                || "日文".equals(source) || "翻译".equals(translated)) {
            return;
        }
        if (TranslationText.hasPlaceholder(source)) {
            addTemplate(source, translated);
        } else {
            putIfAbsent(exact, TranslationText.normalizeKey(source), translated);
            putIfAbsent(exact, TranslationText.compactKey(source), translated);
        }
    }

    void addNoun(String japanese, String translation) {
        String source = TranslationText.normalize(japanese);
        String translated = TranslationText.normalize(translation);
        if (!TranslationText.isUsefulText(source) || translated.isEmpty()
                || "日文".equals(source) || "翻译".equals(translated)) {
            return;
        }
        putIfAbsent(nouns, TranslationText.normalizeKey(source), translated);
        putIfAbsent(nouns, TranslationText.compactKey(source), translated);
    }

    String translate(String japaneseText) {
        String normalized = TranslationText.normalize(japaneseText);
        String direct = exact.get(TranslationText.normalizeKey(normalized));
        if (direct != null) {
            return direct;
        }
        direct = exact.get(TranslationText.compactKey(normalized));
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

    int exactSize() {
        return exact.size();
    }

    int nounSize() {
        return nouns.size();
    }

    int templateSize() {
        int size = wildcardTemplates.size();
        for (List<TranslationTemplate> entries : templates.values()) {
            size += entries.size();
        }
        return size;
    }

    void prepare() {
        Comparator<TranslationTemplate> comparator = new Comparator<TranslationTemplate>() {
            @Override
            public int compare(TranslationTemplate first, TranslationTemplate second) {
                int fixed = second.fixedLength - first.fixedLength;
                if (fixed != 0) {
                    return fixed;
                }
                return second.translationFixedLength - first.translationFixedLength;
            }
        };
        Collections.sort(wildcardTemplates, comparator);
        for (List<TranslationTemplate> entries : templates.values()) {
            Collections.sort(entries, comparator);
        }
    }

    String translateCapturedValue(String value) {
        String translated = nouns.get(TranslationText.normalizeKey(value));
        if (translated != null) {
            return translated;
        }
        translated = nouns.get(TranslationText.compactKey(value));
        return translated == null ? TranslationText.normalize(value) : translated;
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
            List<TranslationTemplate> bucket = templates.get(entry.firstLiteral);
            if (bucket == null) {
                bucket = new ArrayList<TranslationTemplate>();
                templates.put(entry.firstLiteral, bucket);
            }
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

    private static void putIfAbsent(Map<String, String> result, String key, String value) {
        if (!key.isEmpty() && !result.containsKey(key)) {
            result.put(key, value);
        }
    }
}
