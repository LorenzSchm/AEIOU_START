package ai.aeiou.edgelab.gateway;

/**
 * The 5U LLM gateway, the only thing in edgelab that calls out over the network.
 * Tests use a fake; production uses {@link HttpGateway#fromSettings()}.
 *
 * Implementations never throw: every failure (no key, HTTP error, timeout, odd
 * response) comes back as an {@link Answer} with a {@code problem}.
 */
public interface Gateway {

    /** The fixed instruction for {@link #copyText}. */
    String COPY_TEXT_PROMPT = """
            Copy all visible text in this image exactly as written. Keep each table row on one line, \
            with cells separated by " | ". Keep the original language, numbers and units. \
            Do not interpret, summarise, translate or add anything. \
            If there is no readable text, return nothing. \
            After the text, on its own last line, write CONFIDENCE: followed by a number from 0 to 100 \
            for how sure you are that the copy is complete and correct.""";

    /** The fixed instruction for audio sent through chat completions. */
    String TRANSCRIBE_PROMPT = """
            Transcribe this audio exactly, in the language that is spoken. \
            Do not translate, summarise or answer it. Return only the transcript.""";

    /**
     * What came back.
     *
     * @param text       the model's text ("" on failure), with any CONFIDENCE line removed
     * @param selfRating the model's own 0..1 rating, or null if it gave none (audio never does)
     * @param model      the model that was asked, for notes
     * @param problem    null when it worked, else a short reason (never contains the key)
     */
    record Answer(String text, Double selfRating, String model, String problem) {
        public Answer {
            text = text == null ? "" : text;
        }

        public boolean ok() { return problem == null; }

        public static Answer failed(String model, String problem) {
            return new Answer("", null, model, problem);
        }
    }

    /**
     * Speech to text with the gateway audio model.
     *
     * @param audio  the audio file bytes
     * @param format "wav" or "mp3" (what OpenAI-style audio input accepts)
     */
    Answer transcribe(byte[] audio, String format);

    /**
     * Copy all text in an image ({@link #COPY_TEXT_PROMPT}) with the vision model, low detail.
     *
     * @param image    image bytes, already in a format the model takes (jpeg, png, webp, gif)
     * @param mimeType e.g. "image/jpeg"
     */
    Answer copyText(byte[] image, String mimeType);

    /** A gateway that answers every call with the same problem, e.g. "TEAM_API_KEY not set". */
    static Gateway unavailable(String reason) {
        return new Gateway() {
            @Override public Answer transcribe(byte[] audio, String format) { return Answer.failed(null, reason); }
            @Override public Answer copyText(byte[] image, String mimeType) { return Answer.failed(null, reason); }
            @Override public String toString() { return "Gateway.unavailable(" + reason + ")"; }
        };
    }
}
