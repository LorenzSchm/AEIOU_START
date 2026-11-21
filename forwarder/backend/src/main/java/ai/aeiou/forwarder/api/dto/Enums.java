package ai.aeiou.forwarder.api.dto;

/**
 * Wire enums of the control panel API (API_CONTRACT.md). Constants are spelled exactly like the JSON values,
 * the same convention as {@link ai.aeiou.forwarder.event.Actor}, so springdoc emits clean string enums.
 */
public final class Enums {

    private Enums() {}

    public enum SessionType { RFQ, TRACKING, TARIFF, OTHER }

    public enum QuoteMode { cheapest, fastest }

    public enum MailDirection { in, out }

    public enum PriceSource { sheet, portal, bid, ratedesk, truck, combined }

    public enum BidKind { airline, trucker, ratedesk }

    public enum BookingStatus { requested, booked, confirmed, failed }

    public enum TariffSource { kit, mail }

    public enum TariffStatus { active, pending_review, superseded, rejected }

    public enum CarrierKind { airline, trucker }

    public enum SessionActionType { approve, edit_and_send, reroute, take_over, release, resolve }

    /** Lenient parse for free-text columns: unknown or null values become {@code null}. */
    public static <E extends Enum<E>> E parseOrNull(Class<E> type, String value) {
        if (value == null) return null;
        for (E e : type.getEnumConstants()) {
            if (e.name().equalsIgnoreCase(value)) return e;
        }
        return null;
    }
}
