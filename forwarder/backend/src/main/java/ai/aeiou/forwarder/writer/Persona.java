package ai.aeiou.forwarder.writer;

/** The person who signs our mails. Configurable via the settings {@code writerPersonaName} and {@code writerCompany}. */
public record Persona(String fullName, String company) {

    public static final String DEFAULT_NAME = "Lena Hoffmann";
    public static final String DEFAULT_COMPANY = "AEIOU Air Freight, Munich";

    public String firstName() {
        int space = fullName.indexOf(' ');
        return space > 0 ? fullName.substring(0, space) : fullName;
    }

    /** The block appended under the closing line, like a real mail client signature. */
    public String signature() {
        return fullName + "\n" + company;
    }
}
