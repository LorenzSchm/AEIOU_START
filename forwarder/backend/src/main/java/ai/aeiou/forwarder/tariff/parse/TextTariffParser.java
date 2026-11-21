package ai.aeiou.forwarder.tariff.parse;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Plain-text sheets (Yellowtail). UTF-8, falling back to Latin-1 for legacy encodings. */
@Component
@Order(4)
public class TextTariffParser implements TariffParser {

    @Override
    public boolean supports(TariffSource source) {
        return source.format() == TariffSource.Format.TEXT;
    }

    @Override
    public List<String> lines(TariffSource source) {
        return decode(source.content()).lines().toList();
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }
}
