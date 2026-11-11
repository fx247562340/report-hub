package com.reporthub.service;

import com.reporthub.common.BizException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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

            int nRows = rows.size();
            int[][] span = mergeSpans(columns, rows);
            // hidden 列不导出，但仍参与合并跨度计算（如作为月份分层边界）
            List<Integer> vis = new ArrayList<>();
            for (int c = 0; c < columns.size(); c++) {
                Object h = columns.get(c).get("hidden");
                if (!(Boolean.TRUE.equals(h) || "true".equals(String.valueOf(h)))) vis.add(c);
            }

            Row header = sheet.createRow(0);
            for (int i = 0; i < vis.size(); i++) {
                Map<String, Object> col = columns.get(vis.get(i));
                Object label = col.get("label");
                Object key = col.get("key");
                Cell cell = header.createCell(i);
                cell.setCellValue(String.valueOf(label != null ? label : key));
                cell.setCellStyle(headerStyle);
                // 固定列宽：autoSizeColumn 对近万行极慢
                sheet.setColumnWidth(i, 18 * 256);
            }

            for (int r = 0; r < nRows; r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> data = rows.get(r);
                for (int i = 0; i < vis.size(); i++) {
                    int c = vis.get(i);
                    String key = String.valueOf(columns.get(c).get("key"));
                    Cell cell = row.createCell(i);
                    if (span[r][c] == 0) {
                        cell.setBlank();
                        continue;
                    }
                    Object v = data.get(key);
                    if (v == null) {
                        cell.setBlank();
                    } else if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else if (v instanceof Boolean b) {
                        cell.setCellValue(b);
                    } else {
                        cell.setCellValue(String.valueOf(v));
                    }
                    if (span[r][c] > 1) {
                        sheet.addMergedRegion(new CellRangeAddress(r + 1, r + span[r][c], i, i));
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

    /** merge 标记列的同值连续行跨度；0=被合并，不跨上一个 merge 列的分组边界 */
    private static int[][] mergeSpans(List<Map<String, Object>> columns, List<Map<String, Object>> rows) {
        int n = rows.size();
        int[][] span = new int[n][columns.size()];
        for (int[] r : span) Arrays.fill(r, 1);
        int[] parentEnd = new int[n];
        Arrays.fill(parentEnd, n);
        for (int c = 0; c < columns.size(); c++) {
            Object m = columns.get(c).get("merge");
            if (!(Boolean.TRUE.equals(m) || "true".equals(String.valueOf(m)))) continue;
            String key = String.valueOf(columns.get(c).get("key"));
            int[] end = new int[n];
            int i = 0;
            while (i < n) {
                String cur = String.valueOf(rows.get(i).getOrDefault(key, ""));
                int j = i + 1;
                while (j < parentEnd[i] && cur.equals(String.valueOf(rows.get(j).getOrDefault(key, "")))) j++;
                span[i][c] = j - i;
                for (int k = i + 1; k < j; k++) span[k][c] = 0;
                for (int k = i; k < j; k++) end[k] = j;
                i = j;
            }
            parentEnd = end;
        }
        return span;
    }
}
