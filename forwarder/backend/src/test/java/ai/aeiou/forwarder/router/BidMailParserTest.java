package ai.aeiou.forwarder.router;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Real carrier, trucker and rate-desk answers from the 5U mailbox. */
class BidMailParserTest {

    @Test
    void airlineRateWithTransitValidityAndRateId() {
        String body = """
                Dear Team AEIOU,

                Our all-in air freight rate from FRA to ASU is 556.00 EUR. The estimated transit time is 7 days. This rate is \
                valid until 21 Nov 2026 14:58 UTC. Please quote reference R-zcj8CzMYoCcdWaCe when booking.

                Kind regards,
                Zephyr Steppe Cargo""";

        Extraction.Bid bid = BidMailParser.parse("Re: Rate request", body).orElseThrow();

        assertThat(bid.price()).isEqualByComparingTo("556.00");
        assertThat(bid.currency()).isEqualTo("EUR");
        assertThat(bid.transitDays()).isEqualTo(7);
        assertThat(bid.validUntil()).isEqualTo("2026-11-21T14:58Z");
        assertThat(bid.rateId()).isEqualTo("R-zcj8CzMYoCcdWaCe");
        assertThat(bid.declined()).isFalse();
    }

    @Test
    void transitWithColonAndValidityBeforeIt() {
        String body = "Hi Team AEIOU, Our all-in air freight rate from FRA to ASU is 529.00 EUR, valid until 21 Nov 2026 "
                + "15:02 UTC. Estimated transit time: 6 days. Please quote R-6uQituvqTpVTuHy0 when booking. Best regards, "
                + "Himalaya Bridge Air";

        Extraction.Bid bid = BidMailParser.parse("Re: Rate request", body).orElseThrow();

        assertThat(bid.price()).isEqualByComparingTo("529.00");
        assertThat(bid.transitDays()).isEqualTo(6);
        assertThat(bid.rateId()).isEqualTo("R-6uQituvqTpVTuHy0");
    }

    @Test
    void transitTimeOfNDays() {
        String body = "Our all-in airport-to-airport rate from Frankfurt (FRA) to Asunción (ASU) is 556.00 EUR, with an "
                + "estimated transit time of 7 days. This rate is valid until 21 Nov 2026 14:16 UTC.";

        assertThat(BidMailParser.parse("", body)).hasValueSatisfying(b -> assertThat(b.transitDays()).isEqualTo(7));
    }

    @Test
    void truckerOfferWithoutTransitKeepsItsOfferReference() {
        String body = "Hello Team AEIOU, Thanks for reaching out! Our all-in rate for collection in Frankfurt am Main and "
                + "delivery to Frankfurt Airport is 110.00 EUR. Pickup time is 16 hours, and the rate is valid until "
                + "21 Nov 2026 14:16 UTC. Your offer reference is TRK-52. No booking needed yet.";

        Extraction.Bid bid = BidMailParser.parse("", body).orElseThrow();

        assertThat(bid.price()).isEqualByComparingTo("110.00");
        assertThat(bid.transitDays()).isNull();
        assertThat(bid.notes()).contains("TRK-52");
    }

    @Test
    void counterOfferRefusalStillCarriesThePrice() {
        String body = "Thank you for your counteroffer. We cannot revise our quotation. Our best and only all-in rate is "
                + "556.00 EUR. Transit: 7 days.";

        Extraction.Bid bid = BidMailParser.parse("", body).orElseThrow();

        assertThat(bid.declined()).isFalse();
        assertThat(bid.price()).isEqualByComparingTo("556.00");
    }

    @Test
    void clearDecline() {
        Extraction.Bid bid = BidMailParser.parse("", "Dear Team, unfortunately we cannot offer this lane at the moment. "
                + "Kind regards").orElseThrow();

        assertThat(bid.declined()).isTrue();
        assertThat(bid.price()).isNull();
    }

    @Test
    void ambiguousMailsGoToTheLlm() {
        assertThat(BidMailParser.parse("", "Rate is 500.00 EUR for 45 kg and 900.00 EUR for 100 kg, transit 3 days")).isEmpty();
        assertThat(BidMailParser.parse("", "Rate is 500.00 EUR, transit depends on the routing")).isEmpty();
        assertThat(BidMailParser.parse("", "Thanks, we will come back to you.")).isEmpty();
    }

    @Test
    void quotedRequestBelowTheAnswerIsIgnored() {
        String body = """
                Our all-in rate is 556.00 EUR, transit time 7 days.

                On Sat, 21 Nov 2026, Team AEIOU wrote:
                > Budget around 700.00 EUR, 2 days transit?""";

        assertThat(BidMailParser.parse("", body)).hasValueSatisfying(b -> assertThat(b.price()).isEqualByComparingTo("556.00"));
    }
}
