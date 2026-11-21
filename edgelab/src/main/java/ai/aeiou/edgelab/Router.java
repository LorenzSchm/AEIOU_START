package ai.aeiou.edgelab;

import java.util.Locale;
import java.util.Set;

/**
 * Picks the reader for a detected MIME type. One named slot per reader, so each
 * later ticket only replaces its own slot (see {@link #standard()}).
 */
public final class Router {

    /** The reader slots, in the order {@link #slotFor(String)} checks them. */
    public enum Slot { PHOTO, VOICE, VIDEO, CONTAINER, PLAIN_TEXT, OFFICE }

    /** Archives and mail: the container walker opens them and reads each part. */
    static final Set<String> CONTAINER_TYPES = Set.of(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-gzip",
            "application/x-7z-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "message/rfc822",
            "application/vnd.ms-outlook");

    private final FormatReader photo;
    private final FormatReader voice;
    private final FormatReader video;
    private final FormatReader container;
    private final FormatReader plainText;
    private final FormatReader office;

    private Router(FormatReader photo, FormatReader voice, FormatReader video,
                   FormatReader container, FormatReader plainText, FormatReader office) {
        this.photo = photo;
        this.voice = voice;
        this.video = video;
        this.container = container;
        this.plainText = plainText;
        this.office = office;
    }

    /** The production wiring. Later tickets swap their one line here. */
    public static Router standard() {
        return empty()
                .withPhoto(ai.aeiou.edgelab.photo.PhotoReader.standard())  // ticket 05: photo reader
                .withVoice(ai.aeiou.edgelab.voice.VoiceReader.standard())  // ticket 02: voice reader
                .withVideo(FormatReader.notYetSupported("video"))          // ticket: video reader
                .withContainer(FormatReader.notYetSupported("container"))  // ticket: container walker
                .withPlainText(new PlainTextReader())
                // ticket 04: Tika catch-all; ticket 05 wraps it so scanned PDF pages go to the photo reader
                .withOffice(ai.aeiou.edgelab.photo.ScanAwareOfficeReader.standard());
    }

    /** Every slot set to "not yet supported". */
    public static Router empty() {
        return new Router(
                FormatReader.notYetSupported("photo"),
                FormatReader.notYetSupported("voice"),
                FormatReader.notYetSupported("video"),
                FormatReader.notYetSupported("container"),
                FormatReader.notYetSupported("plain text"),
                FormatReader.notYetSupported("office"));
    }

    public Router withPhoto(FormatReader r)     { return new Router(r, voice, video, container, plainText, office); }
    public Router withVoice(FormatReader r)     { return new Router(photo, r, video, container, plainText, office); }
    public Router withVideo(FormatReader r)     { return new Router(photo, voice, r, container, plainText, office); }
    public Router withContainer(FormatReader r) { return new Router(photo, voice, video, r, plainText, office); }
    public Router withPlainText(FormatReader r) { return new Router(photo, voice, video, container, r, office); }
    public Router withOffice(FormatReader r)    { return new Router(photo, voice, video, container, plainText, r); }

    /** Which slot handles this MIME type. Everything not matched goes to OFFICE (the Tika catch-all). */
    public static Slot slotFor(String detectedType) {
        String type = baseType(detectedType);
        if (type.startsWith("image/")) return Slot.PHOTO;
        if (type.startsWith("audio/")) return Slot.VOICE;
        if (type.startsWith("video/")) return Slot.VIDEO;
        if (CONTAINER_TYPES.contains(type)) return Slot.CONTAINER;
        if (type.equals("text/plain")) return Slot.PLAIN_TEXT;
        return Slot.OFFICE;
    }

    public FormatReader readerFor(String detectedType) {
        return switch (slotFor(detectedType)) {
            case PHOTO -> photo;
            case VOICE -> voice;
            case VIDEO -> video;
            case CONTAINER -> container;
            case PLAIN_TEXT -> plainText;
            case OFFICE -> office;
        };
    }

    /** "text/plain; charset=UTF-8" -> "text/plain". */
    static String baseType(String mime) {
        if (mime == null) return "application/octet-stream";
        int semi = mime.indexOf(';');
        return (semi < 0 ? mime : mime.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }
}
