package ai.aeiou.forwarder.mail;

/** 5U refused or failed to send a mail. {@code status} is the HTTP status (0 for network errors). */
public class MailSendException extends RuntimeException {

    private final int status;

    public MailSendException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
