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

/**
 * @Author: Xv
 * @Date: 2026/6/9
 * @Description: 从 XLSX 文本库加载日文对话和中文翻译，并支持固定文本与占位符模板匹配。
 */
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

    /**
     * 按优先级加载外部文本库或 JAR 内置文本库。
     */
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

    /**
     * 翻译 OCR 得到的日文文本。优先整体匹配，失败后按行拆分匹配。
     */
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

    /**
     * 读取 XLSX 并根据表结构选择新版或旧版解析逻辑。
     */
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

    /**
     * 解析旧版 text_clean.xlsx 中的固定对话文本表。
     */
    private static void parseLegacyWorkbook(Workbook workbook, List<String> sharedStrings,
                                            TranslationStore store) throws IOException {
        parseSimpleTextSheet(workbook.entry(workbook.sheetPath("对话文本")), sharedStrings, store, "A", "B");
    }

    /**
     * 解析新版 text.xlsx：先加载名词表，再加载对话和图鉴文本。
     */
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

    /**
     * 判断 sheet 是否应作为名词翻译表处理。
     */
    private static boolean isNounSheet(String sheetName) {
        return !"图".equals(sheetName)
                && !"标".equals(sheetName)
                && !"Sheet".equals(sheetName)
                && !sheetName.matches("文\\d+");
    }

    /**
     * 解析日文列和翻译列一一对应的简单文本表。
     */
    private static void parseSimpleTextSheet(byte[] xml, List<String> sharedStrings, TranslationStore store,
                                             String japaneseColumn, String translationColumn) throws IOException {
        for (RowValues row : parseRows(xml, sharedStrings)) {
            store.addEntry(row.value(japaneseColumn), row.value(translationColumn));
        }
    }

    /**
     * 解析名词表，跳过标记为不使用的行。
     */
    private static void parseNounSheet(byte[] xml, List<String> sharedStrings, TranslationStore store)
            throws IOException {
        for (RowValues row : parseRows(xml, sharedStrings)) {
            String skip = trim(row.value("F"));
            if (!"Y".equalsIgnoreCase(skip)) {
                store.addNoun(row.value("C"), row.value("D"));
            }
        }
    }

    /**
     * 解析对话 sheet，并按分段标记把连续行聚合成文本块。
     */
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

    /**
     * 解析图鉴 sheet，兼容图鉴文本中的分页和空行分段。
     */
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

    /**
     * 判断当前行是否为对话分段头。
     */
    private static boolean isDialogHeader(RowValues row) {
        return trim(row.value("A")).startsWith("|---英文")
                || trim(row.value("C")).startsWith("|---日文");
    }

    /**
     * 将一个文本块写入翻译库，同时补充整段和相邻两行窗口的匹配项。
     */
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

    /**
     * 为游戏中常见的双行对话窗口生成额外匹配项。
     */
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

    /**
     * 过滤空行、分隔标记和非实际游戏文本。
     */
    private static boolean isUsefulText(String value) {
        String text = trim(value);
        return !text.isEmpty()
                && !text.startsWith("|---")
                && !text.startsWith("--图鉴")
                && !text.startsWith("--２页")
                && !text.startsWith("--开始");
    }

    /**
     * 将 XLSX 当作 zip 读取，只保留解析文本所需的 XML 条目。
     */
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

    /**
     * 判断 zip 条目是否需要读入内存。
     */
    private static boolean shouldReadEntry(String name) {
        return "xl/workbook.xml".equals(name)
                || "xl/_rels/workbook.xml.rels".equals(name)
                || "xl/sharedStrings.xml".equals(name)
                || name.startsWith("xl/worksheets/sheet");
    }

    /**
     * 解析 XLSX sharedStrings.xml，得到共享字符串表。
     */
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

    /**
     * 解析 worksheet XML，并按列名保存每行单元格文本。
     */
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

    /**
     * 读取单元格文本，兼容 shared string、inline string 和普通值。
     */
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

    /**
     * 从 A1、BC23 这样的单元格引用中提取列名。
     */
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

    /**
     * 安全解析 XML，禁用外部实体避免 XXE 风险。
     */
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

    /**
     * 尽可能关闭 XML 外部实体和 DTD 加载。
     */
    private static void disableExternalEntities(DocumentBuilderFactory factory) {
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setExpandEntityReferences(false);
    }

    /**
     * 某些 Java 8 XML 实现不支持全部安全特性，因此这里按 best effort 设置。
     */
    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException ignored) {
            // Java 8 XML providers vary; unsupported hardening flags can be ignored here.
        }
    }

    /**
     * 读取输入流全部字节。
     */
    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * 安静关闭资源，用于 zip 流清理路径。
     */
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 统一文本键格式：NFC、换行规范化、全角空格转半角并裁剪行首尾。
     */
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

    /**
     * 标准精确匹配 key。
     */
    private static String normalizeKey(String value) {
        return normalizeText(value);
    }

    /**
     * 去掉所有空白后的紧凑 key，用于容错匹配游戏换行和空格差异。
     */
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

    /**
     * 拼接多行文本并跳过空行。
     */
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

    /**
     * 语义别名，表示输入会先标准化再拼接。
     */
    private static String joinNormalized(Collection<String> values) {
        return join(values);
    }

    /**
     * 去掉字符串首尾空白；null 按空字符串处理。
     */
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

    /**
     * 返回第一个非空文本，常用于新版表中翻译列的兜底选择。
     */
    private static String firstNonEmpty(String first, String second) {
        return trim(first).isEmpty() ? second : first;
    }

    /**
     * 判断文本是否包含 <...> 或【...】形式的占位符。
     */
    private static boolean hasPlaceholder(String text) {
        return PLACEHOLDER.matcher(text).find();
    }

    /**
     * 翻译数据索引，包含 O(1) 精确匹配、名词表和预编译模板。
     */
    private static final class TranslationStore {
        private final Map<String, String> exact = new HashMap<String, String>();
        private final Map<String, String> nouns = new HashMap<String, String>();
        private final Map<Character, List<TemplateEntry>> templates = new HashMap<Character, List<TemplateEntry>>();
        private final List<TemplateEntry> wildcardTemplates = new ArrayList<TemplateEntry>();
        private final Set<String> templateKeys = new HashSet<String>();

        /**
         * 添加普通对话翻译；含占位符文本会编译为模板。
         */
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

        /**
         * 添加名词翻译，用于替换模板捕获到的动态值。
         */
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

        /**
         * 先查精确 key，再按首字符分桶查模板，最后查通配模板。
         */
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

        /**
         * 按固定文本长度排序模板，让更具体的模板优先匹配。
         */
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

        /**
         * 编译并保存一个占位符模板。
         */
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

        /**
         * 依次尝试候选模板，返回第一个成功翻译结果。
         */
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

        /**
         * 翻译模板捕获到的动态名词；名词表未命中则保留原文。
         */
        private String translateCapturedValue(String value) {
            String translated = nouns.get(normalizeKey(value));
            if (translated != null) {
                return translated;
            }
            translated = nouns.get(compactKey(value));
            return translated == null ? normalizeText(value) : translated;
        }

        /**
         * 获取第一个非空白字符，用作模板分桶 key。
         */
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

    /**
     * 预编译的占位符翻译模板。
     */
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

        /**
         * 将含占位符的日文模板编译为正则表达式。
         */
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

        /**
         * 尝试匹配日文文本，并把捕获到的占位符值回填到中文模板。
         */
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

        /**
         * 获取模板开头的第一个字面量字符。
         */
        private static char firstLiteral(String value) {
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (!Character.isWhitespace(character)) {
                    return character;
                }
            }
            return 0;
        }

        /**
         * 将模板字面量加入正则，空白位置按宽松规则匹配。
         */
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

        /**
         * 将累积的普通文本按字面量追加到正则中。
         */
        private static void appendQuoted(StringBuilder regex, StringBuilder plain) {
            if (plain.length() > 0) {
                regex.append(Pattern.quote(plain.toString()));
                plain.setLength(0);
            }
        }

        /**
         * 统计翻译模板中的固定文本长度，用于模板优先级排序。
         */
        private static int fixedTextLength(String template) {
            String normalized = normalizeText(template);
            Matcher matcher = PLACEHOLDER.matcher(normalized);
            String withoutPlaceholders = matcher.replaceAll("");
            return withoutPlaceholders.replace(" ", "").replace("\n", "").length();
        }
    }

    /**
     * 一个文本块中的日文/翻译行。
     */
    private static final class BlockLine {
        final String japanese;
        final String translation;

        BlockLine(String japanese, String translation) {
            this.japanese = japanese;
            this.translation = translation;
        }
    }

    /**
     * worksheet 中一行按列名索引后的值。
     */
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

    /**
     * XLSX 工作簿的轻量视图，负责 sheet 名称和 XML 路径映射。
     */
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

        /**
         * 解析 workbook 关系文件，得到关系 id 到目标路径的映射。
         */
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

        /**
         * 解析 sheet 名称，并通过关系映射得到实际 worksheet XML 路径。
         */
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

    /**
     * 只在 key 非空且尚未存在时写入，避免后续重复行覆盖先前结果。
     */
    private static void putIfAbsent(Map<String, String> result, String key, String value) {
        if (!key.isEmpty() && !result.containsKey(key)) {
            result.put(key, value);
        }
    }
}
