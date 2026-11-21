package ai.aeiou.forwarder.tariff.parse;

import java.util.Locale;

/** A tariff file as received (kit file or mail attachment). */
public record TariffSource(String filename, String contentType, byte[] content) {

    public enum Format { XLSX, DOCX, PDF, TEXT, IMAGE, UNKNOWN }

    public Format format() {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || type.contains("spreadsheetml")) return Format.XLSX;
        if (name.endsWith(".docx") || type.contains("wordprocessingml")) return Format.DOCX;
        if (name.endsWith(".pdf") || type.equals("application/pdf")) return Format.PDF;
        if (name.endsWith(".txt") || name.endsWith(".csv") || type.startsWith("text/")) return Format.TEXT;
        if (name.matches(".*\\.(jpe?g|png|gif|tiff?|bmp|webp)$") || type.startsWith("image/")) return Format.IMAGE;
        return Format.UNKNOWN;
    }
}
