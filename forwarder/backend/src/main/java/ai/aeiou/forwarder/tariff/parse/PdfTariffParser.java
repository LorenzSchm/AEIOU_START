package ai.aeiou.forwarder.tariff.parse;

import ai.aeiou.forwarder.tariff.TariffParseException;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Text PDFs (Cobalt, Helix, Orion). Text is extracted in reading order; scanned PDFs yield no lanes and fail. */
@Component
@Order(3)
public class PdfTariffParser implements TariffParser {

    @Override
    public boolean supports(TariffSource source) {
        return source.format() == TariffSource.Format.PDF;
    }

    @Override
    public List<String> lines(TariffSource source) throws TariffParseException {
        try (PDDocument doc = Loader.loadPDF(source.content())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).lines().toList();
        } catch (IOException | RuntimeException e) {
            throw new TariffParseException("Cannot read pdf " + source.filename() + ": " + e.getMessage(), e);
        }
    }
}
