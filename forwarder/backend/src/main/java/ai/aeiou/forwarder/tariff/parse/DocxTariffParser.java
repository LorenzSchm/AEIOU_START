package ai.aeiou.forwarder.tariff.parse;

import ai.aeiou.forwarder.tariff.TariffParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Word documents (Quetzal): paragraphs become lines, table rows become tab-separated lines, in document order. */
@Component
@Order(2)
public class DocxTariffParser implements TariffParser {

    @Override
    public boolean supports(TariffSource source) {
        return source.format() == TariffSource.Format.DOCX;
    }

    @Override
    public List<String> lines(TariffSource source) throws TariffParseException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(source.content()))) {
            List<String> lines = new ArrayList<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    lines.addAll(p.getText().lines().toList());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String text = cell.getText().replaceAll("\\s+", " ").trim();
                            if (!text.isEmpty()) cells.add(text);
                        }
                        lines.add(String.join("\t", cells));
                    }
                }
            }
            return lines;
        } catch (IOException | RuntimeException e) {
            throw new TariffParseException("Cannot read docx " + source.filename() + ": " + e.getMessage(), e);
        }
    }
}
