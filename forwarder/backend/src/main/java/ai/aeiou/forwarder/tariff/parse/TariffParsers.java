package ai.aeiou.forwarder.tariff.parse;

import ai.aeiou.forwarder.reference.ReferenceData;
import ai.aeiou.forwarder.tariff.ParsedTariff;
import ai.aeiou.forwarder.tariff.TariffParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Picks the parser for a file and resolves city-only lane names against the airport table. */
@Component
public class TariffParsers {

    private final List<TariffParser> parsers;
    private final AirportResolver airports;

    @Autowired
    public TariffParsers(List<TariffParser> parsers, ReferenceData referenceData) {
        this(parsers, referenceData::iataForCityOrCode);
    }

    TariffParsers(List<TariffParser> parsers, AirportResolver airports) {
        this.parsers = List.copyOf(parsers);
        this.airports = airports;
    }

    /** Default set of format parsers, for use outside Spring (tests, tools). */
    public static TariffParsers standard(AirportResolver airports) {
        return new TariffParsers(List.of(new XlsxTariffParser(), new DocxTariffParser(), new PdfTariffParser(), new TextTariffParser()), airports);
    }

    public Optional<TariffParser> parserFor(TariffSource source) {
        return parsers.stream().filter(p -> p.supports(source)).findFirst();
    }

    public ParsedTariff parse(TariffSource source) throws TariffParseException {
        TariffParser parser = parserFor(source)
                .orElseThrow(() -> new TariffParseException(List.of("No parser for " + source.filename() + " (" + source.format() + ")")));
        return parser.parse(source, airports);
    }

    /** Plain text of the document for the LLM fallback; empty when the format cannot be read as text. */
    public String text(TariffSource source) {
        Optional<TariffParser> parser = parserFor(source);
        if (parser.isEmpty()) return "";
        try {
            return String.join("\n", parser.get().lines(source));
        } catch (TariffParseException e) {
            return "";
        }
    }
}
