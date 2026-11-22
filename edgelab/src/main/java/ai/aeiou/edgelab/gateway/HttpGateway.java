package ai.aeiou.edgelab.gateway;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI-compatible client for the 5U gateway ({@code <base>/chat/completions},
 * {@code <base>/audio/transcriptions}). Authenticates with {@code Authorization: Bearer
 * TEAM_API_KEY}. Logs nothing, and the key never appears in an {@link Gateway.Answer}.
 */
public final class HttpGateway implements Gateway {

    /** "CONFIDENCE: 87" on the last line of a copyText answer. */
    private static final Pattern CONFIDENCE_LINE =
            Pattern.compile("(?im)^\\s*\\**\\s*CONFIDENCE\\s*\\**\\s*:\\s*\\**\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*%?\\s*\\**\\s*$");

    private final GatewaySettings settings;
    private final HttpClient http;

    public HttpGateway(GatewaySettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
    }

    /**
     * The production gateway. Without a key, or with {@code edgelab.gateway.enabled=false},
     * it is {@link Gateway#unavailable} with a clear reason instead.
     */
    public static Gateway fromSettings() {
        if (!GatewaySettings.enabled()) {
            return Gateway.unavailable("gateway disabled (edgelab.gateway.enabled=false)");
        }
        GatewaySettings s = GatewaySettings.fromEnvironment();
        if (!s.hasKey()) return Gateway.unavailable(GatewaySettings.KEY_NAME + " not set");
        return new HttpGateway(s);
    }

    public GatewaySettings settings() { return settings; }

    @Override
    public Answer transcribe(byte[] audio, String format) {
        String model = settings.audioModel();
        if (audio == null || audio.length == 0) return Answer.failed(model, "no audio to send");
        String fmt = format == null ? "wav" : format.toLowerCase(Locale.ROOT);
        return settings.audioMethod() == GatewaySettings.AudioMethod.TRANSCRIPTIONS
                ? transcribeViaEndpoint(audio, fmt, model)
                : transcribeViaChat(audio, fmt, model);
    }

    private Answer transcribeViaChat(byte[] audio, String fmt, String model) {
        String body = "{\"model\":" + Json.quote(model)
                + ",\"modalities\":[\"text\"],\"temperature\":0"
                + ",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":" + Json.quote(TRANSCRIBE_PROMPT) + "},"
                + "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\""
                + Base64.getEncoder().encodeToString(audio) + "\",\"format\":" + Json.quote(fmt) + "}}"
                + "]}]}";
        return chat(body, model, false);
    }

    private Answer transcribeViaEndpoint(byte[] audio, String fmt, String model) {
        String boundary = "edgelab-" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 512);
        writeAscii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n");
        writeAscii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n");
        writeAscii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio." + fmt
                + "\"\r\nContent-Type: " + ("mp3".equals(fmt) ? "audio/mpeg" : "audio/" + fmt) + "\r\n\r\n");
        out.writeBytes(audio);
        writeAscii(out, "\r\n--" + boundary + "--\r\n");
        byte[] multipart = out.toByteArray();
        return send(() -> request("/audio/transcriptions")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .build(), model, json -> {
            Object text = Json.path(json, "text");
            return text instanceof String t ? new Answer(t.strip(), null, model, null) : null;
        });
    }

    @Override
    public Answer copyText(byte[] image, String mimeType) {
        String model = settings.visionModel();
        if (image == null || image.length == 0) return Answer.failed(model, "no image to send");
        String dataUrl = "data:" + (mimeType == null ? "image/png" : mimeType) + ";base64,"
                + Base64.getEncoder().encodeToString(image);
        String body = "{\"model\":" + Json.quote(model) + ",\"temperature\":0,\"max_tokens\":2000"
                + ",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":" + Json.quote(COPY_TEXT_PROMPT) + "},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + dataUrl + "\",\"detail\":\"low\"}}"
                + "]}]}";
        return chat(body, model, true);
    }

    private Answer chat(String body, String model, boolean selfRated) {
        return send(() -> request("/chat/completions")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), model, json -> {
            String content = contentOf(Json.path(json, "choices", 0, "message", "content"));
            if (content == null) return null;
            return selfRated ? splitSelfRating(content, model) : new Answer(content.strip(), null, model, null);
        });
    }

    /** content is a string, or a list of {type:text, text:...} parts. */
    private static String contentOf(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof List<?> parts) {
            StringBuilder b = new StringBuilder();
            for (Object p : parts) {
                if (Json.path(p, "text") instanceof String t) b.append(t);
            }
            return b.toString();
        }
        return null;
    }

    static Answer splitSelfRating(String content, String model) {
        Matcher m = CONFIDENCE_LINE.matcher(content);
        Double rating = null;
        String text = content;
        int lastStart = -1;
        while (m.find()) {
            lastStart = m.start();
            rating = Math.max(0, Math.min(100, Double.parseDouble(m.group(1)))) / 100.0;
        }
        if (lastStart >= 0) text = content.substring(0, lastStart);
        return new Answer(text.strip(), rating, model, null);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(settings.baseUrl() + path))
                .timeout(settings.timeout())
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Accept", "application/json");
    }

    private interface Reader { Answer read(Object json); }

    /** {@code request} is built inside the try: a bad base URL or key must come back as an Answer, not throw. */
    private Answer send(java.util.function.Supplier<HttpRequest> request, String model, Reader reader) {
        if (!settings.hasKey()) return Answer.failed(model, GatewaySettings.KEY_NAME + " not set");
        HttpRequest req;
        try {
            req = request.get();
        } catch (RuntimeException e) {
            // Never the message: it can quote the header value, i.e. the key.
            return Answer.failed(model, "gateway request could not be built (check the base URL and "
                    + GatewaySettings.KEY_NAME + "): " + e.getClass().getSimpleName());
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            ai.aeiou.edgelab.ReadSession.retryAfterServiceFailure();
            return Answer.failed(model, "gateway timed out after " + settings.timeout().toSeconds() + " s");
        } catch (InterruptedException e) {
            ai.aeiou.edgelab.ReadSession.retryAfterServiceFailure();
            Thread.currentThread().interrupt();
            return Answer.failed(model, "gateway call interrupted");
        } catch (Exception e) {
            ai.aeiou.edgelab.ReadSession.retryAfterServiceFailure();
            return Answer.failed(model, "gateway call failed: " + e.getClass().getSimpleName());
        }
        if (resp.statusCode() / 100 != 2) {
            if (resp.statusCode() == 429 || resp.statusCode() >= 500)
                ai.aeiou.edgelab.ReadSession.retryAfterServiceFailure();
            return Answer.failed(model, "gateway HTTP " + resp.statusCode() + errorDetail(resp.body()));
        }
        try {
            Answer a = reader.read(Json.parse(resp.body()));
            return a != null ? a : Answer.failed(model, "gateway answer had no text");
        } catch (RuntimeException e) {
            return Answer.failed(model, "gateway answer was not valid JSON");
        }
    }

    /** ": <error.message>", short, with anything key-like removed. */
    private String errorDetail(String body) {
        String msg = null;
        try {
            Object json = Json.parse(body);
            if (Json.path(json, "error", "message") instanceof String s) msg = s;
            else if (Json.path(json, "error") instanceof String s) msg = s;
            else if (Json.path(json, "detail") instanceof String s) msg = s;
        } catch (RuntimeException ignored) {
            // not JSON; no detail
        }
        if (msg == null || msg.isBlank()) return "";
        if (settings.apiKey() != null) msg = msg.replace(settings.apiKey(), "***");
        msg = msg.replaceAll("\\s+", " ").strip();
        return ": " + (msg.length() > 160 ? msg.substring(0, 160) + "..." : msg);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "HttpGateway[" + settings + "]";
    }
}
