package com.reporthub.service;

import com.reporthub.common.BizException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

public final class ExcelExportService {
    private ExcelExportService() {}

    public static byte[] toXlsx(List<Map<String, Object>> columns,
                                List<Map<String, Object>> rows,
                                String sheetName) {
        try (Workbook wb = new XSSFWorkbook()) {
            String name = sheetName == null || sheetName.isBlank() ? "Sheet1" : sheetName;
            if (name.length() > 31) name = name.substring(0, 31);
            Sheet sheet = wb.createSheet(name);

            CellStyle headerStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Object label = columns.get(i).get("label");
                Object key = columns.get(i).get("key");
                Cell cell = header.createCell(i);
                cell.setCellValue(String.valueOf(label != null ? label : key));
                cell.setCellStyle(headerStyle);
                // 固定列宽：autoSizeColumn 对近万行极慢
                sheet.setColumnWidth(i, 18 * 256);
            }

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> data = rows.get(r);
                for (int c = 0; c < columns.size(); c++) {
                    String key = String.valueOf(columns.get(c).get("key"));
                    Object v = data.get(key);
                    Cell cell = row.createCell(c);
                    if (v == null) {
                        cell.setBlank();
                    } else if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else if (v instanceof Boolean b) {
                        cell.setCellValue(b);
                    } else {
                        cell.setCellValue(String.valueOf(v));
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("导出 Excel 失败: " + e.getMessage());
        }
    }
}
