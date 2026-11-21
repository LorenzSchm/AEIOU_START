package ai.aeiou.forwarder.writer;

/**
 * Well-known keys for {@link EmailWriter.MailIntent#facts()}. Callers may add any other key; these are the ones the
 * fallback templates and the validator understand.
 */
public final class Facts {

    private Facts() {}

    /** All-in total as display string, e.g. "1,387.35". Together with {@link #CURRENCY} this is the one quoted price. */
    public static final String TOTAL = "total";
    public static final String CURRENCY = "currency";
    public static final String AIRLINE = "airline";
    public static final String TRANSIT_DAYS = "transitDays";
    public static final String VALID_UNTIL = "validUntil";
    public static final String ORIGIN = "origin";
    public static final String DESTINATION = "destination";
    public static final String WEIGHT_KG = "weightKg";
    public static final String VOLUME_CBM = "volumeCbm";
    public static final String CHARGEABLE_KG = "chargeableKg";
    public static final String PIECES = "pieces";
    public static final String COMMODITY = "commodity";
    public static final String READY_DATE = "readyDate";
    public static final String PICKUP_ZIP = "pickupZip";
    public static final String PICKUP_CITY = "pickupCity";
    public static final String AIRPORT = "airport";
    public static final String OUR_REFERENCE = "ourReference";
    public static final String BOOKING_REFERENCE = "bookingReference";
    public static final String STATUS = "status";
    public static final String DEPARTURE = "departure";
    public static final String ETA = "eta";
    /** One plain sentence about where the shipment is now, computed from the CRM (tracking). */
    public static final String PROGRESS = "progress";
    public static final String TODAY = "today";
    /** For holding replies: when we expect to come back, e.g. "within the hour". */
    public static final String NEXT_UPDATE = "nextUpdate";
}
