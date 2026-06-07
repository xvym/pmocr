package io.github.xvym.pmocr;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class XlsxTranslationRepository {
    private static final String DEFAULT_FILE = "text_clean.xlsx";
    private static final String DIALOG_SHEET = "对话文本";
    private static final String NOT_FOUND = "无文本";

    private final Map<String, String> translations;
    private final String source;

    private XlsxTranslationRepository(Map<String, String> translations, String source) {
        this.translations = translations;
        this.source = source;
    }

    static XlsxTranslationRepository loadDefault() {
        File file = new File(DEFAULT_FILE);
        if (file.isFile()) {
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                return load(input, file.getPath());
            } catch (IOException e) {
                return empty(file.getPath() + " 加载失败: " + e.getMessage());
            }
        }
        InputStream resource = XlsxTranslationRepository.class.getResourceAsStream("/" + DEFAULT_FILE);
        if (resource != null) {
            try (InputStream input = resource) {
                return load(input, "JAR:" + DEFAULT_FILE);
            } catch (IOException e) {
                return empty("JAR:" + DEFAULT_FILE + " 加载失败: " + e.getMessage());
            }
        }
        return empty("未找到 " + DEFAULT_FILE);
    }

    String translate(String japaneseText) {
        String normalized = normalizeText(japaneseText);
        if (normalized.isEmpty()) {
            return NOT_FOUND;
        }

        String direct = lookup(normalized);
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
            String translated = lookup(trimmed);
            result.append(translated == null ? NOT_FOUND : translated);
            hasLine = true;
        }
        return hasLine ? result.toString() : NOT_FOUND;
    }

    int size() {
        return translations.size();
    }

    String source() {
        return source;
    }

    private String lookup(String text) {
        String exact = translations.get(normalizeKey(text));
        if (exact != null) {
            return exact;
        }
        return translations.get(compactKey(text));
    }

    private static XlsxTranslationRepository empty(String source) {
        return new XlsxTranslationRepository(new HashMap<String, String>(), source);
    }

    private static XlsxTranslationRepository load(InputStream input, String source) throws IOException {
        Workbook workbook = readWorkbook(input);
        String sheetPath = workbook.sheetPath(DIALOG_SHEET);
        if (sheetPath == null) {
            throw new IOException("找不到工作表: " + DIALOG_SHEET);
        }
        List<String> sharedStrings = parseSharedStrings(workbook.entry("xl/sharedStrings.xml"));
        Map<String, String> rows = parseSheet(workbook.entry(sheetPath), sharedStrings);
        return new XlsxTranslationRepository(rows, source);
    }

    private static Workbook readWorkbook(InputStream input) throws IOException {
        Map<String, byte[]> entries = new HashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(input);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), readAll(zip));
                }
            }
        } finally {
            closeQuietly(zip);
        }
        return new Workbook(entries);
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

    private static Map<String, String> parseSheet(byte[] xml, List<String> sharedStrings) throws IOException {
        if (xml == null) {
            throw new IOException("工作表 XML 不存在");
        }
        Map<String, String> result = new HashMap<String, String>();
        Document document = parseXml(xml);
        NodeList rows = document.getElementsByTagName("row");
        for (int i = 0; i < rows.getLength(); i++) {
            NodeList cells = ((Element) rows.item(i)).getElementsByTagName("c");
            String japanese = null;
            String translation = null;
            for (int j = 0; j < cells.getLength(); j++) {
                Element cell = (Element) cells.item(j);
                String column = columnName(cell.getAttribute("r"));
                if (!"A".equals(column) && !"B".equals(column)) {
                    continue;
                }
                String value = cellValue(cell, sharedStrings);
                if ("A".equals(column)) {
                    japanese = value;
                } else {
                    translation = value;
                }
            }
            addRow(result, japanese, translation);
        }
        return result;
    }

    private static void addRow(Map<String, String> result, String japanese, String translation) {
        String source = normalizeText(japanese);
        String translated = normalizeText(translation);
        if (source.isEmpty() || translated.isEmpty()
                || "日文".equals(source) || "翻译".equals(translated)) {
            return;
        }
        putIfAbsent(result, normalizeKey(source), translated);
        putIfAbsent(result, compactKey(source), translated);
    }

    private static void putIfAbsent(Map<String, String> result, String key, String value) {
        if (!key.isEmpty() && !result.containsKey(key)) {
            result.put(key, value);
        }
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

    private static String trim(String value) {
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

    private static final class Workbook {
        private final Map<String, byte[]> entries;
        private final Map<String, String> relationships;
        private final Map<String, String> sheets;

        Workbook(Map<String, byte[]> entries) throws IOException {
            this.entries = entries;
            this.relationships = parseRelationships(entry("xl/_rels/workbook.xml.rels"));
            this.sheets = parseSheets(entry("xl/workbook.xml"), relationships);
        }

        byte[] entry(String path) {
            return entries.get(path);
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
                String target = relationships.get(sheet.getAttribute("r:id"));
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
