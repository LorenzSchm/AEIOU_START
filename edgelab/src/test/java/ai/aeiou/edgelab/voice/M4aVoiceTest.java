package ai.aeiou.edgelab.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * m4a with its index ("moov") at the end, as phones often record, must decode. No Whisper here;
 * the end-to-end transcription lives in VoiceReaderTest so all runs share one Whisper model.
 */
class M4aVoiceTest {

    @ParameterizedTest
    @ValueSource(strings = {M4aFixtures.MOOV_AT_END, M4aFixtures.FASTSTART})
    void fixtureReallyHasItsIndexWhereTheNameSays(String layout) {
        byte[] m4a = M4aFixtures.get(layout);
        int moov = indexOf(m4a, "moov");
        int mdat = indexOf(m4a, "mdat");
        assertTrue(moov > 0 && mdat > 0);
        assertEquals(layout.equals(M4aFixtures.MOOV_AT_END), moov > mdat, "moov at " + moov + ", mdat at " + mdat);
    }

    @ParameterizedTest
    @ValueSource(strings = {M4aFixtures.MOOV_AT_END, M4aFixtures.FASTSTART})
    void decoderGetsAllTheSoundOutOfAnM4a(String layout) {
        AudioDecoder.Result r = new AudioDecoder(120).decode(M4aFixtures.get(layout));

        assertTrue(r.ok(), r.problem());
        // rfq-en.wav is about 15 s long.
        assertTrue(r.seconds() > 14, "only " + r.seconds() + " s");
    }

    private static int indexOf(byte[] haystack, String box) {
        byte[] needle = box.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
