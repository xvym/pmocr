package io.github.xvym.pmocr.translation;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.poi.ss.usermodel.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * @Author: Xv
 * @Date: 2026/6/9
 * @Description: 从标准 XLSX 文本库加载日文文本、名词和中文翻译。
 */
@Data
@AllArgsConstructor
public final class XlsxTranslationRepository {
    private static final String NOT_FOUND = "无文本";
    private static final String TEXT_RESOURCE = "text/text_clean_1.xlsx";
    private static final String TEXT_SHEET = "文本";
    private static final String JAPANESE_HEADER = "日文";
    private static final String TRANSLATION_HEADER = "翻译";

    private TranslationIndex store;

    /**
     * 加载文本库。
     */
    public static XlsxTranslationRepository loadDefault() {
        try (InputStream input = XlsxTranslationRepository.class.getResourceAsStream("/" + TEXT_RESOURCE)) {
            if (input == null) {
                throw new IOException("文本库资源不存在");
            }

            TranslationIndex store = new TranslationIndex();
            try (Workbook workbook = WorkbookFactory.create(input)) {
                parseTextWorkbook(workbook, store);
            }
            store.prepare();
            return new XlsxTranslationRepository(store);
        } catch (Exception e) {
            return new XlsxTranslationRepository(new TranslationIndex());
        }
    }

    /**
     * 翻译 OCR 得到的日文文本。优先整体匹配，失败后按行拆分匹配。
     */
    public String translate(String japaneseText) {
        String normalized = TranslationTextUtils.normalize(japaneseText);
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
            String trimmed = TranslationTextUtils.trim(line);
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

    /**
     * 标准文本库约定：
     * - “文本” sheet 保存日文/翻译条目；
     * - 其他 sheet 保存名词表，表头同样使用“日文”“翻译”。
     */
    private static void parseTextWorkbook(Workbook workbook, TranslationIndex store) {
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (!TEXT_SHEET.equals(sheet.getSheetName())) {
                parsePairSheet(sheet, formatter, evaluator, store, true);
            }
        }

        Sheet textSheet = workbook.getSheet(TEXT_SHEET);
        if (textSheet != null) {
            parsePairSheet(textSheet, formatter, evaluator, store, false);
        }
    }

    /**
     * 按表头读取“日文”“翻译”两列，避免依赖固定列号。
     */
    private static void parsePairSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, TranslationIndex store, boolean nounSheet) {
        Header header = findHeader(sheet, formatter, evaluator);
        if (header == null) {
            return;
        }

        for (int rowIndex = header.rowNumber + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            String japanese = cellText(row, header.japaneseColumn, formatter, evaluator);
            String translation = cellText(row, header.translationColumn, formatter, evaluator);
            if (nounSheet) {
                store.addNoun(japanese, translation);
            } else {
                store.addEntry(japanese, translation);
            }
        }
    }

    private static Header findHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Row row : sheet) {
            Map<String, Integer> columns = new HashMap<>();
            for (Cell cell : row) {
                String value = TranslationTextUtils.trim(formatter.formatCellValue(cell, evaluator));
                if (!value.isEmpty()) {
                    columns.put(value, cell.getColumnIndex());
                }
            }
            Integer japanese = columns.get(JAPANESE_HEADER);
            Integer translation = columns.get(TRANSLATION_HEADER);
            if (japanese != null && translation != null) {
                return new Header(row.getRowNum(), japanese, translation);
            }
        }
        return null;
    }

    /**
     * 读取单元格显示文本。缺失单元格按空字符串处理。
     */
    private static String cellText(Row row, int columnIndex, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator);
    }

    private static final class Header {
        final int rowNumber;
        final int japaneseColumn;
        final int translationColumn;

        Header(int rowNumber, int japaneseColumn, int translationColumn) {
            this.rowNumber = rowNumber;
            this.japaneseColumn = japaneseColumn;
            this.translationColumn = translationColumn;
        }
    }
}
