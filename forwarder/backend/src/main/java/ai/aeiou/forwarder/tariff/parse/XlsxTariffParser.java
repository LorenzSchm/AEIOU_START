package ai.aeiou.forwarder.tariff.parse;

import ai.aeiou.forwarder.tariff.TariffParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Excel sheets (Elbflug, Kestrel, Vega, Wayzata). Numbers are taken from the raw cell value, not the
 * displayed format, so "0.00"-formatted cells keep full precision; text cells ("3,87") are left to the reader.
 * Hidden sheets and hidden rows are skipped: only what the airline shows is part of the tariff.
 */
@Component
@Order(1)
public class XlsxTariffParser implements TariffParser {

    @Override
    public boolean supports(TariffSource source) {
        return source.format() == TariffSource.Format.XLSX;
    }

    @Override
    public List<String> lines(TariffSource source) throws TariffParseException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(source.content()))) {
            List<String> lines = new ArrayList<>();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) continue;
                Sheet sheet = wb.getSheetAt(s);
                for (Row row : sheet) {
                    if (row.getZeroHeight()) continue;
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        if (sheet.isColumnHidden(cell.getColumnIndex())) continue;
                        String text = text(cell);
                        if (!text.isBlank()) cells.add(text.trim());
                    }
                    lines.add(String.join("\t", cells));
                }
                lines.add("");
            }
            return lines;
        } catch (IOException | RuntimeException e) {
            throw new TariffParseException("Cannot read xlsx " + source.filename() + ": " + e.getMessage(), e);
        }
    }

    private static String text(Cell cell) {
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
