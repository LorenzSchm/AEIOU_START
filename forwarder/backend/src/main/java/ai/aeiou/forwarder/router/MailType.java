package ai.aeiou.forwarder.router;

public enum MailType {
    NEW_RFQ,
    ACCEPTANCE,            // customer accepts our quote -> book
    FOLLOW_UP,             // customer writes again in an RFQ thread (question, change, rejection)
    CARRIER_BID,           // airline / trucker answers our bid request
    RATEDESK_REPLY,        // ratedesk@hackloop.mail answers
    BOOKING_CONFIRMATION,  // "Booking BK-xxxxxxxx confirmed"
    TARIFF,                // airline sends a (monthly) tariff sheet
    TRACKING_QUESTION,     // tracking@hackloop.mail or a customer asks where a shipment is
    OTHER
}
