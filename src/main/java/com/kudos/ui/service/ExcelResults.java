/*
 * Copyright 2026 Aleksey Martynov and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kudos.ui.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Writes a {@link QueryResult} as an .xlsx workbook. Uses POI's streaming
 * {@code SXSSF} so a large result does not have to be held in memory as a
 * document tree while it is written out.
 */
public final class ExcelResults {

  private ExcelResults() {}

  public static void write(QueryResult result, OutputStream out) throws IOException {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
      SXSSFSheet sheet = workbook.createSheet("Results");

      CellStyle headerStyle = workbook.createCellStyle();
      Font bold = workbook.createFont();
      bold.setBold(true);
      headerStyle.setFont(bold);

      List<String> columns = result.columns();
      Row header = sheet.createRow(0);
      for (int c = 0; c < columns.size(); c++) {
        Cell cell = header.createCell(c);
        cell.setCellValue(columns.get(c));
        cell.setCellStyle(headerStyle);
      }

      int rowIndex = 1;
      for (List<Object> row : result.rows()) {
        Row sheetRow = sheet.createRow(rowIndex++);
        for (int c = 0; c < row.size(); c++) {
          Object value = row.get(c);
          Cell cell = sheetRow.createCell(c);
          if (value == null) {
            cell.setBlank();
          } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
          } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
          } else {
            cell.setCellValue(value.toString());
          }
        }
      }

      sheet.createFreezePane(0, 1);
      workbook.write(out);
      workbook.dispose();
    }
  }
}
