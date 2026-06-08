package io.github.xvym.pmocr;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class XlsxTranslationRepository {
    private static final String NOT_FOUND = "无文本";
    private static final String MODERN_TEXT_FILE = "text.xlsx";
    private static final String LEGACY_TEXT_FILE = "text_clean.xlsx";
    private static final String USER_TEXT_FILE = "D:\\Code\\Workspace\\PokeGSC_SharedXLSXCN\\text.xlsx";
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]+>|【[^】]+】");

    private final TranslationStore store;
    private final String source;

    private XlsxTranslationRepository(TranslationStore store, String source) {
        this.store = store;
        this.source = source;
    }

    static XlsxTranslationRepository loadDefault() {
        String[] files = {MODERN_TEXT_FILE, USER_TEXT_FILE, LEGACY_TEXT_FILE};
        for (String name : files) {
            File file = new File(name);
            if (file.isFile()) {
                try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                    return load(input, file.getPath());
                } catch (IOException e) {
                    return empty(file.getPath() + " 加载失败: " + e.getMessage());
                }
            }
        }

        String[] resources = {MODERN_TEXT_FILE, LEGACY_TEXT_FILE};
        for (String name : resources) {
            InputStream resource = XlsxTranslationRepository.class.getResourceAsStream("/" + name);
            if (resource != null) {
                try (InputStream input = resource) {
                    return load(input, "JAR:" + name);
                } catch (IOException e) {
                    return empty("JAR:" + name + " 加载失败: " + e.getMessage());
                }
            }
        }
        return empty("未找到文本库");
    }

    String translate(String japaneseText) {
        String normalized = normalizeText(japaneseText);
        if (normalized.isEmpty()) {
            return NOT_FOUND;
        }

        String direct = store.translate(normalized);
        if (direct != null) {
            return direct;
        }

        String[] lines = normalized.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean hasLine = false;
        for (String line : lines) {
            String trimmed = trim(line);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            String translated = store.translate(trimmed);
            result.append(translated == null ? NOT_FOUND : translated);
            hasLine = true;
        }
        return hasLine ? result.toString() : NOT_FOUND;
    }

    int size() {
        return store.exactSize() + store.templateSize();
    }

    int nounSize() {
        return store.nounSize();
    }

    int templateSize() {
        return store.templateSize();
    }

    String source() {
        return source;
    }

    private static XlsxTranslationRepository empty(String source) {
        return new XlsxTranslationRepository(new TranslationStore(), source);
    }

    private static XlsxTranslationRepository load(InputStream input, String source) throws IOException {
        Workbook workbook = readWorkbook(input);
        List<String> sharedStrings = parseSharedStrings(workbook.entry("xl/sharedStrings.xml"));
        TranslationStore store = new TranslationStore();
        if (workbook.hasSheet("对话文本")) {
            parseLegacyWorkbook(workbook, sharedStrings, store);
        } else {
            parseModernWorkbook(workbook, sharedStrings, store);
        }
        store.prepare();
        return new XlsxTranslationRepository(store, source);
    }

    private static void parseLegacyWorkbook(Workbook workbook, List<String> sharedStrings,
                                            TranslationStore store) throws IOException {
        parseSimpleTextSheet(workbook.entry(workbook.sheetPath("对话文本")), sharedStrings, store, "A", "B");
    }

    private static void parseModernWorkbook(Workbook workbook, List<String> sharedStrings,
                                            TranslationStore store) throws IOException {
        for (String sheetName : workbook.sheetNames()) {
            if (isNounSheet(sheetName)) {
                parseNounSheet(workbook.entry(workbook.sheetPath(sheetName)), sharedStrings, store);
            }
        }
        for (String sheetName : workbook.sheetNames()) {
            if (sheetName.matches("文\\d+")) {
                parseDialogSheet(workbook.entry(workbook.sheetPath(sheetName)), sharedStrings, store);
            } else if ("图".equals(sheetName)) {
                parsePokedexSheet(workbook.entry(workbook.sheetPath(sheetName)), sharedStrings, store);
            }
        }
    }

    private static boolean isNounSheet(String sheetName) {
        return !"图".equals(sheetName)
                && !"标".equals(sheetName)
                && !"Sheet".equals(sheetName)
                && !sheetName.matches("文\\d+");
    }

    private static void parseSimpleTextSheet(byte[] xml, List<String> sharedStrings, TranslationStore store,
                                             String japaneseColumn, String translationColumn) throws IOException {
        for (RowValues row : parseRows(xml, sharedStrings)) {
            store.addEntry(row.value(japaneseColumn), row.value(translationColumn));
        }
    }

    private static void parseNounSheet(byte[] xml, List<String> sharedStrings, TranslationStore store)
            throws IOException {
        for (RowValues row : parseRows(xml, sharedStrings)) {
            String skip = trim(row.value("F"));
            if (!"Y".equalsIgnoreCase(skip)) {
                store.addNoun(row.value("C"), row.value("D"));
            }
        }
    }

    private static void parseDialogSheet(byte[] xml, List<String> sharedStrings, TranslationStore store)
            throws IOException {
        List<BlockLine> block = new ArrayList<BlockLine>();
        for (RowValues row : parseRows(xml, sharedStrings)) {
            if (isDialogHeader(row)) {
                flushBlock(block, store);
                block.clear();
                continue;
            }
            block.add(new BlockLine(row.value("C"), row.value("E")));
        }
        flushBlock(block, store);
    }

    private static void parsePokedexSheet(byte[] xml, List<String> sharedStrings, TranslationStore store)
            throws IOException {
        List<BlockLine> block = new ArrayList<BlockLine>();
        for (RowValues row : parseRows(xml, sharedStrings)) {
            String marker = trim(row.value("A"));
            if (marker.startsWith("--开始")) {
                flushBlock(block, store);
                block.clear();
                continue;
            }
            String japanese = row.value("C");
            String translation = firstNonEmpty(row.value("E"), row.value("D"));
            if (trim(japanese).isEmpty() && trim(translation).isEmpty()) {
                flushBlock(block, store);
                block.clear();
                continue;
            }
            block.add(new BlockLine(japanese, translation));
        }
        flushBlock(block, store);
    }

    private static boolean isDialogHeader(RowValues row) {
        return trim(row.value("A")).startsWith("|---英文")
                || trim(row.value("C")).startsWith("|---日文");
    }

    private static void flushBlock(List<BlockLine> block, TranslationStore store) {
        if (block.isEmpty()) {
            return;
        }

        List<String> japaneseLines = new ArrayList<String>();
        List<String> translationLines = new ArrayList<String>();
        for (BlockLine line : block) {
            if (isUsefulText(line.japanese)) {
                japaneseLines.add(normalizeText(line.japanese));
            }
            if (isUsefulText(line.translation)) {
                translationLines.add(normalizeText(line.translation));
            }
            store.addEntry(line.japanese, line.translation);
        }
        store.addEntry(join(japaneseLines), join(translationLines));
        addTwoLineWindows(block, store);
    }

    private static void addTwoLineWindows(List<BlockLine> block, TranslationStore store) {
        List<Integer> japaneseIndexes = new ArrayList<Integer>();
        for (int i = 0; i < block.size(); i++) {
            if (isUsefulText(block.get(i).japanese)) {
                japaneseIndexes.add(i);
            }
        }
        for (int i = 0; i + 1 < japaneseIndexes.size(); i++) {
            int first = japaneseIndexes.get(i);
            int second = japaneseIndexes.get(i + 1);
            List<String> japanese = new ArrayList<String>();
            japanese.add(block.get(first).japanese);
            japanese.add(block.get(second).japanese);

            List<String> translation = new ArrayList<String>();
            for (int row = first; row <= second; row++) {
                if (isUsefulText(block.get(row).translation)) {
                    translation.add(block.get(row).translation);
                }
            }
            if (translation.isEmpty()) {
                translation.add(block.get(first).translation);
                translation.add(block.get(second).translation);
            }
            store.addEntry(joinNormalized(japanese), joinNormalized(translation));
        }
    }

    private static boolean isUsefulText(String value) {
        String text = trim(value);
        return !text.isEmpty()
                && !text.startsWith("|---")
                && !text.startsWith("--图鉴")
                && !text.startsWith("--２页")
                && !text.startsWith("--开始");
    }

    private static Workbook readWorkbook(InputStream input) throws IOException {
        Map<String, byte[]> entries = new HashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(input);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && shouldReadEntry(entry.getName())) {
                    entries.put(entry.getName(), readAll(zip));
                }
            }
        } finally {
            closeQuietly(zip);
        }
        return new Workbook(entries);
    }

    private static boolean shouldReadEntry(String name) {
        return "xl/workbook.xml".equals(name)
                || "xl/_rels/workbook.xml.rels".equals(name)
                || "xl/sharedStrings.xml".equals(name)
                || name.startsWith("xl/worksheets/sheet");
    }

    private static List<String> parseSharedStrings(byte[] xml) throws IOException {
        List<String> result = new ArrayList<String>();
        if (xml == null) {
            return result;
        }
        Document document = parseXml(xml);
        NodeList items = document.getElementsByTagName("si");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            NodeList texts = item.getElementsByTagName("t");
            StringBuilder value = new StringBuilder();
            for (int j = 0; j < texts.getLength(); j++) {
                value.append(texts.item(j).getTextContent());
            }
            result.add(value.toString());
        }
        return result;
    }

    private static List<RowValues> parseRows(byte[] xml, List<String> sharedStrings) throws IOException {
        List<RowValues> result = new ArrayList<RowValues>();
        if (xml == null) {
            return result;
        }
        Document document = parseXml(xml);
        NodeList rows = document.getElementsByTagName("row");
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            RowValues values = new RowValues();
            NodeList cells = row.getElementsByTagName("c");
            for (int j = 0; j < cells.getLength(); j++) {
                Element cell = (Element) cells.item(j);
                values.put(columnName(cell.getAttribute("r")), cellValue(cell, sharedStrings));
            }
            result.add(values);
        }
        return result;
    }

    private static String cellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList texts = cell.getElementsByTagName("t");
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < texts.getLength(); i++) {
                value.append(texts.item(i).getTextContent());
            }
            return value.toString();
        }

        NodeList values = cell.getElementsByTagName("v");
        if (values.getLength() == 0) {
            return "";
        }
        String raw = values.item(0).getTextContent();
        if ("s".equals(type)) {
            int index = Integer.parseInt(raw);
            return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
        }
        return raw;
    }

    private static String columnName(String reference) {
        StringBuilder column = new StringBuilder();
        for (int i = 0; i < reference.length(); i++) {
            char value = reference.charAt(i);
            if (value >= 'A' && value <= 'Z') {
                column.append(value);
            } else {
                break;
            }
        }
        return column.toString();
    }

    private static Document parseXml(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            disableExternalEntities(factory);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new ByteArrayInputStream(xml)));
        } catch (ParserConfigurationException e) {
            throw new IOException("XML 解析器配置失败", e);
        } catch (SAXException e) {
            throw new IOException("XML 解析失败", e);
        }
    }

    private static void disableExternalEntities(DocumentBuilderFactory factory) {
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setExpandEntityReferences(false);
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException ignored) {
            // Java 8 XML providers vary; unsupported hardening flags can be ignored here.
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static String normalizeText(String value) {
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

    private static String normalizeKey(String value) {
        return normalizeText(value);
    }

    private static String compactKey(String value) {
        String normalized = normalizeText(value);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (!Character.isWhitespace(character)) {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String join(Collection<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            String normalized = normalizeText(value);
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

    private static String joinNormalized(Collection<String> values) {
        return join(values);
    }

    private static String trim(String value) {
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

    private static String firstNonEmpty(String first, String second) {
        return trim(first).isEmpty() ? second : first;
    }

    private static boolean hasPlaceholder(String text) {
        return PLACEHOLDER.matcher(text).find();
    }

    private static final class TranslationStore {
        private final Map<String, String> exact = new HashMap<String, String>();
        private final Map<String, String> nouns = new HashMap<String, String>();
        private final Map<Character, List<TemplateEntry>> templates = new HashMap<Character, List<TemplateEntry>>();
        private final List<TemplateEntry> wildcardTemplates = new ArrayList<TemplateEntry>();
        private final Set<String> templateKeys = new HashSet<String>();

        void addEntry(String japanese, String translation) {
            String source = normalizeText(japanese);
            String translated = normalizeText(translation);
            if (!isUsefulText(source) || translated.isEmpty()
                    || "日文".equals(source) || "翻译".equals(translated)) {
                return;
            }
            if (hasPlaceholder(source)) {
                addTemplate(source, translated);
            } else {
                putIfAbsent(exact, normalizeKey(source), translated);
                putIfAbsent(exact, compactKey(source), translated);
            }
        }

        void addNoun(String japanese, String translation) {
            String source = normalizeText(japanese);
            String translated = normalizeText(translation);
            if (!isUsefulText(source) || translated.isEmpty()
                    || "日文".equals(source) || "翻译".equals(translated)) {
                return;
            }
            putIfAbsent(nouns, normalizeKey(source), translated);
            putIfAbsent(nouns, compactKey(source), translated);
        }

        String translate(String japaneseText) {
            String normalized = normalizeText(japaneseText);
            String direct = exact.get(normalizeKey(normalized));
            if (direct != null) {
                return direct;
            }
            direct = exact.get(compactKey(normalized));
            if (direct != null) {
                return direct;
            }

            char first = firstNonWhitespace(normalized);
            List<TemplateEntry> candidates = templates.get(first);
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
            for (List<TemplateEntry> entries : templates.values()) {
                size += entries.size();
            }
            return size;
        }

        void prepare() {
            Comparator<TemplateEntry> comparator = new Comparator<TemplateEntry>() {
                @Override
                public int compare(TemplateEntry first, TemplateEntry second) {
                    int fixed = second.fixedLength - first.fixedLength;
                    if (fixed != 0) {
                        return fixed;
                    }
                    return second.translationFixedLength - first.translationFixedLength;
                }
            };
            Collections.sort(wildcardTemplates, comparator);
            for (List<TemplateEntry> entries : templates.values()) {
                Collections.sort(entries, comparator);
            }
        }

        private void addTemplate(String japanese, String translation) {
            String key = japanese + "\u0000" + translation;
            if (!templateKeys.add(key)) {
                return;
            }
            TemplateEntry entry = TemplateEntry.compile(japanese, translation, this);
            if (entry.startsWithPlaceholder) {
                wildcardTemplates.add(entry);
            } else {
                List<TemplateEntry> bucket = templates.get(entry.firstLiteral);
                if (bucket == null) {
                    bucket = new ArrayList<TemplateEntry>();
                    templates.put(entry.firstLiteral, bucket);
                }
                bucket.add(entry);
            }
        }

        private String matchTemplates(List<TemplateEntry> entries, String japaneseText) {
            if (entries == null) {
                return null;
            }
            for (TemplateEntry entry : entries) {
                String translated = entry.tryTranslate(japaneseText);
                if (translated != null) {
                    return translated;
                }
            }
            return null;
        }

        private String translateCapturedValue(String value) {
            String translated = nouns.get(normalizeKey(value));
            if (translated != null) {
                return translated;
            }
            translated = nouns.get(compactKey(value));
            return translated == null ? normalizeText(value) : translated;
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

    private static final class TemplateEntry {
        final Pattern pattern;
        final String translationTemplate;
        final List<String> placeholders;
        final TranslationStore store;
        final char firstLiteral;
        final boolean startsWithPlaceholder;
        final int fixedLength;
        final int translationFixedLength;

        private TemplateEntry(Pattern pattern, String translationTemplate, List<String> placeholders,
                              TranslationStore store, char firstLiteral, boolean startsWithPlaceholder,
                              int fixedLength, int translationFixedLength) {
            this.pattern = pattern;
            this.translationTemplate = translationTemplate;
            this.placeholders = placeholders;
            this.store = store;
            this.firstLiteral = firstLiteral;
            this.startsWithPlaceholder = startsWithPlaceholder;
            this.fixedLength = fixedLength;
            this.translationFixedLength = translationFixedLength;
        }

        static TemplateEntry compile(String japaneseTemplate, String translationTemplate,
                                     TranslationStore store) {
            String normalized = normalizeText(japaneseTemplate);
            Matcher matcher = PLACEHOLDER.matcher(normalized);
            StringBuilder regex = new StringBuilder();
            List<String> placeholders = new ArrayList<String>();
            regex.append("^\\s*");
            int index = 0;
            char firstLiteral = 0;
            boolean startsWithPlaceholder = false;
            int fixedLength = 0;
            while (matcher.find()) {
                if (matcher.start() == 0) {
                    startsWithPlaceholder = true;
                }
                String literal = normalized.substring(index, matcher.start());
                fixedLength += literal.replace(" ", "").replace("\n", "").length();
                if (firstLiteral == 0) {
                    firstLiteral = firstLiteral(literal);
                }
                appendFlexibleLiteral(regex, literal);
                placeholders.add(matcher.group());
                regex.append("(.+?)");
                index = matcher.end();
            }
            String tail = normalized.substring(index);
            fixedLength += tail.replace(" ", "").replace("\n", "").length();
            if (firstLiteral == 0) {
                firstLiteral = firstLiteral(tail);
            }
            appendFlexibleLiteral(regex, tail);
            regex.append("\\s*$");
            return new TemplateEntry(Pattern.compile(regex.toString()), normalizeText(translationTemplate),
                    placeholders, store, firstLiteral, startsWithPlaceholder || firstLiteral == 0, fixedLength,
                    fixedTextLength(translationTemplate));
        }

        String tryTranslate(String japaneseText) {
            Matcher matcher = pattern.matcher(normalizeText(japaneseText));
            if (!matcher.matches()) {
                return null;
            }
            final Map<String, String> values = new HashMap<String, String>();
            for (int i = 0; i < placeholders.size(); i++) {
                String token = placeholders.get(i);
                if (!values.containsKey(token)) {
                    values.put(token, store.translateCapturedValue(matcher.group(i + 1)));
                }
            }
            Matcher replacementMatcher = PLACEHOLDER.matcher(translationTemplate);
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
            String normalized = normalizeText(template);
            Matcher matcher = PLACEHOLDER.matcher(normalized);
            String withoutPlaceholders = matcher.replaceAll("");
            return withoutPlaceholders.replace(" ", "").replace("\n", "").length();
        }
    }

    private static final class BlockLine {
        final String japanese;
        final String translation;

        BlockLine(String japanese, String translation) {
            this.japanese = japanese;
            this.translation = translation;
        }
    }

    private static final class RowValues {
        private final Map<String, String> values = new HashMap<String, String>();

        void put(String column, String value) {
            values.put(column, value);
        }

        String value(String column) {
            String value = values.get(column);
            return value == null ? "" : value;
        }
    }

    private static final class Workbook {
        private final Map<String, byte[]> entries;
        private final Map<String, String> sheets;

        Workbook(Map<String, byte[]> entries) throws IOException {
            this.entries = entries;
            this.sheets = parseSheets(entry("xl/workbook.xml"), parseRelationships(entry("xl/_rels/workbook.xml.rels")));
        }

        byte[] entry(String path) {
            return entries.get(path);
        }

        boolean hasSheet(String sheetName) {
            return sheets.containsKey(sheetName);
        }

        Collection<String> sheetNames() {
            return sheets.keySet();
        }

        String sheetPath(String sheetName) {
            return sheets.get(sheetName);
        }

        private static Map<String, String> parseRelationships(byte[] xml) throws IOException {
            Map<String, String> result = new HashMap<String, String>();
            Document document = parseXml(xml);
            NodeList relationships = document.getElementsByTagName("Relationship");
            for (int i = 0; i < relationships.getLength(); i++) {
                Element relationship = (Element) relationships.item(i);
                result.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
            }
            return result;
        }

        private static Map<String, String> parseSheets(byte[] xml, Map<String, String> relationships)
                throws IOException {
            Map<String, String> result = new HashMap<String, String>();
            Document document = parseXml(xml);
            NodeList sheets = document.getElementsByTagName("sheet");
            for (int i = 0; i < sheets.getLength(); i++) {
                Element sheet = (Element) sheets.item(i);
                String relationId = sheet.getAttribute("r:id");
                String target = relationships.get(relationId);
                if (target == null) {
                    continue;
                }
                if (target.startsWith("/")) {
                    target = target.substring(1);
                } else {
                    target = "xl/" + target;
                }
                result.put(sheet.getAttribute("name"), target.replace('\\', '/'));
            }
            return result;
        }
    }

    private static void putIfAbsent(Map<String, String> result, String key, String value) {
        if (!key.isEmpty() && !result.containsKey(key)) {
            result.put(key, value);
        }
    }
}
