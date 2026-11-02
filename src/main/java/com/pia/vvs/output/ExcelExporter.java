package com.pia.vvs.output;

import com.pia.vvs.analysis.BranchFlow;
import com.pia.vvs.analysis.ProcessPath;
import com.pia.vvs.model.BpmnProcess;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Exporta las mismas filas que los CSV, sin volver a analizar el modelo. */
public class ExcelExporter {
    /** branches == null indica que la vista por ramas no está soportada. */
    public void write(Path destination, BpmnProcess process, List<ProcessPath> paths,
                      List<BranchFlow> branches) throws IOException {
        Path temporary = null;
        try {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination)) {
                throw new IOException("La salida no es un archivo regular: " + destination);
            }
            FlowTable table = new FlowTable();
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                CellStyle body = workbook.createCellStyle();
                body.setWrapText(true);
                body.setVerticalAlignment(VerticalAlignment.TOP);
                CellStyle header = workbook.createCellStyle();
                header.cloneStyleFrom(body);
                var font = workbook.createFont();
                font.setBold(true);
                header.setFont(font);
                header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
                header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

                addSheet(workbook, "Caminos", table.rows(process, paths), header, body);
                List<List<String>> branchRows = table.branchRows(process, branches == null ? List.of() : branches);
                if (branches == null) {
                    branchRows.add(List.of("NO DISPONIBLE: vista por ramas no soportada para este modelo."));
                }
                addSheet(workbook, "Ramas", branchRows, header, body);
                Path parent = destination.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                temporary = Files.createTempFile(parent, "excel-", ".tmp");
                try (var stream = Files.newOutputStream(temporary)) {
                    workbook.write(stream);
                }
            }
            // Publicar únicamente después de escribir y cerrar el libro completo.
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IOException("No se pudo exportar Excel a " + destination + ": " + exception.getMessage(), exception);
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void addSheet(XSSFWorkbook workbook, String name, List<List<String>> rows,
                          CellStyle header, CellStyle body) {
        var sheet = workbook.createSheet(name);
        int columns = rows.get(0).size();
        int[] widths = new int[columns];
        for (int r = 0; r < rows.size(); r++) {
            var row = sheet.createRow(r);
            List<String> values = rows.get(r);
            int heightLines = 1;
            for (int c = 0; c < values.size(); c++) {
                String value = values.get(c);
                var cell = row.createCell(c);
                if (name.equals("Caminos") && r > 0 && c == 2) {
                    cell.setCellValue(Integer.parseInt(value)); // Retornos: único dato numérico de la tabla.
                } else {
                    cell.setCellValue(value); // STRING incluso si el texto comienza por =, +, - o @.
                }
                cell.setCellStyle(r == 0 ? header : body);
                int lines = 0;
                for (String line : value.split("\n", -1)) {
                    widths[c] = Math.max(widths[c], Math.min(40, line.length() + 2));
                    lines += Math.max(1, (line.length() + 35) / 36);
                }
                heightLines = Math.max(heightLines, lines);
            }
            row.setHeightInPoints(Math.min(150, 18 * heightLines));
        }
        for (int c = 0; c < columns; c++) {
            sheet.setColumnWidth(c, Math.max(14, widths[c]) * 256);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, rows.size() - 1, 0, columns - 1));
    }
}
