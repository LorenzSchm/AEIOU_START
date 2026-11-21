package ai.aeiou.edgelab.alarm;

import java.util.Set;

/** Which edgelab reader fits a file, by its ending and content type. */
public final class Readers {

    private static final Set<String> IMAGE = Set.of("jpg", "jpeg", "png", "heic", "heif", "webp", "tif", "tiff", "gif", "bmp");
    private static final Set<String> AUDIO = Set.of("mp3", "wav", "m4a", "ogg", "opus", "flac", "aac", "amr");
    private static final Set<String> VIDEO = Set.of("mp4", "mov", "avi", "mkv", "m4v", "3gp");
    private static final Set<String> CONTAINER = Set.of("zip", "7z", "rar", "tar", "gz", "eml", "msg");
    private static final Set<String> OFFICE = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
            "odp", "rtf", "html", "htm", "xml", "epub");
    private static final Set<String> TEXT = Set.of("txt", "csv", "md", "json", "tsv");

    private Readers() {}

    public static String suggest(String extension, String contentType) {
        String ext = extension == null ? "" : extension.toLowerCase();
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ext.equals("webm")) return ct.startsWith("audio/") ? "voice reader" : "video reader";
        if (IMAGE.contains(ext) || ct.startsWith("image/")) return "photo reader";
        if (AUDIO.contains(ext) || ct.startsWith("audio/")) return "voice reader";
        if (VIDEO.contains(ext) || ct.startsWith("video/")) return "video reader";
        if (CONTAINER.contains(ext) || ct.contains("zip") || ct.startsWith("message/")) return "container walker";
        if (TEXT.contains(ext) || ct.equals("text/plain") || ct.equals("text/csv")) return "plain text reader";
        if (OFFICE.contains(ext) || ct.startsWith("application/")) return "office reader (Tika)";
        return "no reader fits yet - new reader needed";
    }
}
