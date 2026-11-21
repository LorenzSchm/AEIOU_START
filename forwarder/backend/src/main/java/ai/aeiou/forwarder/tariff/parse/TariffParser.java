package ai.aeiou.forwarder.tariff.parse;

import ai.aeiou.forwarder.tariff.ParsedTariff;
import ai.aeiou.forwarder.tariff.TariffParseException;
import java.util.List;

/**
 * One parser per file format. Each turns its format into plain text lines (table cells separated by tabs)
 * and hands them to the shared {@link TariffLayoutReader}, which understands every kit layout
 * (row tables, per-lane blocks, origin groups, inline or separate surcharges).
 */
public interface TariffParser {

    boolean supports(TariffSource source);

    /** Text lines of the document, also used as LLM input when the deterministic read fails. */
    List<String> lines(TariffSource source) throws TariffParseException;

    default ParsedTariff parse(TariffSource source, AirportResolver airports) throws TariffParseException {
        return TariffLayoutReader.read(lines(source), airports);
    }
}
