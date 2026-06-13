package io.github.xvym.pmocr.translation;

import lombok.Data;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @Author: Xv
 * @Date: 2026/6/9
 * @Description: 从 XLSX 文本库加载日文对话和中文翻译，并支持固定文本与占位符模板匹配。
 */
@Data
public final class XlsxTranslationRepository {
    private static final String NOT_FOUND = "无文本";
    private static final String TEXT_RESOURCE = "text/text.xlsx";
    private final TranslationIndex store;
    private final String source;

    private XlsxTranslationRepository(TranslationIndex store, String source) {
        this.store = store;
        this.source = source;
    }

    /**
     * 加载文本库
     */
    public static XlsxTranslationRepository loadDefault() {
        try (InputStream input = XlsxTranslationRepository.class.getResourceAsStream("/" + TEXT_RESOURCE)) {
            // 读取文本库文件
            Workbook workbook = readWorkbook(input);
            List<String> sharedStrings = parseSharedStrings(workbook.entry("xl/sharedStrings.xml"));
            TranslationIndex store = new TranslationIndex();
            parseTextWorkbook(workbook, sharedStrings, store);
            store.prepare();
            return new XlsxTranslationRepository(store, String.format("JAR:%s", TEXT_RESOURCE));
        } catch (Exception e) {
            return new XlsxTranslationRepository(new TranslationIndex(), String.format("JAR:%s 加载失败:%s", TEXT_RESOURCE, e.getMessage()));
        }
    }

    /**
     * 翻译 OCR 得到的日文文本。优先整体匹配，失败后按行拆分匹配。
     */
    public String translate(String japaneseText) {
        String normalized = TranslationText.normalize(japaneseText);
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

    public int size() {
        return store.exactSize() + store.templateSize();
    }

    public int nounSize() {
        return store.nounSize();
    }

    public int templateSize() {
        return store.templateSize();
    }

    static XlsxTranslationRepository fromIndex(TranslationIndex store, String source) {
        store.prepare();
        return new XlsxTranslationRepository(store, source);
    }

    /**
     * 解析 text.xlsx：先加载名词表，再加载对话和图鉴文本。
     */
    private static void parseTextWorkbook(Workbook workbook, List<String> sharedStrings, TranslationIndex store) throws IOException {
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
     * 解析名词表，跳过标记为不使用的行。
     */
    private static void parseNounSheet(byte[] xml, List<String> sharedStrings, TranslationIndex store)
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
    private static void parseDialogSheet(byte[] xml, List<String> sharedStrings, TranslationIndex store)
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
    private static void parsePokedexSheet(byte[] xml, List<String> sharedStrings, TranslationIndex store)
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
    private static void flushBlock(List<BlockLine> block, TranslationIndex store) {
        if (block.isEmpty()) {
            return;
        }

        List<String> japaneseLines = new ArrayList<String>();
        List<String> translationLines = new ArrayList<String>();
        for (BlockLine line : block) {
            if (isUsefulText(line.japanese)) {
                japaneseLines.add(TranslationText.normalize(line.japanese));
            }
            if (isUsefulText(line.translation)) {
                translationLines.add(TranslationText.normalize(line.translation));
            }
            store.addEntry(line.japanese, line.translation);
        }
        store.addEntry(join(japaneseLines), join(translationLines));
        addTwoLineWindows(block, store);
    }

    /**
     * 为游戏中常见的双行对话窗口生成额外匹配项。
     */
    private static void addTwoLineWindows(List<BlockLine> block, TranslationIndex store) {
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
        return TranslationText.isUsefulText(value);
    }

    /**
     * 将 XLSX 当作 zip 读取，只保留解析文本所需的 XML 条目。
     */
    private static Workbook readWorkbook(InputStream input) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && shouldReadEntry(entry.getName())) {
                    entries.put(entry.getName(), readAll(zip));
                }
            }
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
        List<String> result = new ArrayList<>();
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
     * 拼接多行文本并跳过空行。
     */
    private static String join(Collection<String> values) {
        return TranslationText.join(values);
    }

    /**
     * 语义别名，表示输入会先标准化再拼接。
     */
    private static String joinNormalized(Collection<String> values) {
        return TranslationText.join(values);
    }

    /**
     * 去掉字符串首尾空白；null 按空字符串处理。
     */
    private static String trim(String value) {
        return TranslationText.trim(value);
    }

    /**
     * 返回第一个非空文本，常用于新版表中翻译列的兜底选择。
     */
    private static String firstNonEmpty(String first, String second) {
        return TranslationText.firstNonEmpty(first, second);
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

}
