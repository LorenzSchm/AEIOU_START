package ai.aeiou.forwarder.router;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.router.Stage1Classifier.Carrier;
import ai.aeiou.forwarder.router.Stage1Classifier.Facts;
import ai.aeiou.forwarder.router.Stage1Classifier.Result;
import org.junit.jupiter.api.Test;

class Stage1ClassifierTest {

    private static final Carrier AIRLINE = new Carrier(1, "Helix Air Freight", "airline");

    private final Stage1Classifier stage1 = new Stage1Classifier();

    @Test
    void rateDeskIsDecidedButStillExtracted() {
        Result r = stage1.classify(facts("RateDesk@hackloop.mail", "Re: rate FRA-JFK", false, null, null, null));

        assertThat(r.type()).isEqualTo(MailType.RATEDESK_REPLY);
        assertThat(r.needsStage2()).isTrue();
    }

    @Test
    void trackingDeskIsATrackingQuestion() {
        Result r = stage1.classify(facts("tracking@hackloop.mail", "Where is my cargo?", false, null, null, null));

        assertThat(r.type()).isEqualTo(MailType.TRACKING_QUESTION);
    }

    @Test
    void bookingConfirmationSubjectNeedsNoLlm() {
        Result r = stage1.classify(facts("helix@hackloop.mail", "Booking BK-7Q2X9KLM confirmed", false, AIRLINE, null, null));

        assertThat(r.type()).isEqualTo(MailType.BOOKING_CONFIRMATION);
        assertThat(r.needsStage2()).isFalse();
        assertThat(Stage1Classifier.bookingReference("RE: booking bk-7q2x9klm Confirmed")).contains("BK-7Q2X9KLM");
    }

    @Test
    void carrierReplyToOurBidRequestIsABidAndGoesToStage2ForExtraction() {
        Result r = stage1.classify(facts("helix@hackloop.mail", "Re: bid request", false, AIRLINE, 42L, 42L));

        assertThat(r.type()).isEqualTo(MailType.CARRIER_BID);
        assertThat(r.needsStage2()).isTrue();
    }

    @Test
    void unsolicitedCarrierMailWithAttachmentIsATariff() {
        Result r = stage1.classify(facts("helix@hackloop.mail", "December rates", true, AIRLINE, null, null));

        assertThat(r.type()).isEqualTo(MailType.TARIFF);
        assertThat(r.needsStage2()).isFalse();
    }

    @Test
    void carrierAttachmentInsideOurThreadIsLeftToStage2() {
        Result r = stage1.classify(facts("helix@hackloop.mail", "Re: shipment", true, AIRLINE, null, 7L));

        assertThat(r.type()).isNull();
        assertThat(r.needsStage2()).isTrue();
        assertThat(r.hints()).anyMatch(h -> h.contains("Helix Air Freight"));
    }

    @Test
    void customerMailsAlwaysGoToStage2WithHints() {
        Result fresh = stage1.classify(facts("buyer@acme.example", "Quote FRA to JFK", false, null, null, null));
        Result inThread = stage1.classify(facts("buyer@acme.example", "Re: Quote", false, null, null, 9L));

        assertThat(fresh.type()).isNull();
        assertThat(fresh.needsStage2()).isTrue();
        assertThat(fresh.hints()).anyMatch(h -> h.contains("New thread"));
        assertThat(inThread.hints()).anyMatch(h -> h.contains("existing threads (session type RFQ)"));
    }

    private static Facts facts(String from, String subject, boolean attachments, Carrier carrier,
                               Long bidSession, Long threadSession) {
        return new Facts(from, subject, attachments, carrier, bidSession, threadSession,
                threadSession == null ? null : "RFQ", null);
    }
}
