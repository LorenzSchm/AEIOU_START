package ai.aeiou.forwarder.rfq;

import ai.aeiou.forwarder.settings.SettingsService;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Typed view of the runtime settings the RFQ workflow reads. Every value can change between two RFQs. */
@Component
public class RfqSettings {

    /** Ask every airline serving the destination for a bid, or only those we cannot price ourselves. */
    public enum BidPolicy { all, missing_only }

    public static final String RATEDESK_ADDRESS = "ratedesk@hackloop.mail";

    private final SettingsService settings;

    public RfqSettings(SettingsService settings) {
        this.settings = settings;
    }

    /** How long we wait for bids after the RFQ arrived (setting {@code bidWaitSeconds}, default 90). */
    public Duration bidWait() {
        return Duration.ofSeconds(settings.getInt("bidWaitSeconds", 90));
    }

    /** Latest decision point after the RFQ arrived (setting {@code quoteDeadlineSeconds}, default 300). */
    public Duration quoteDeadline() {
        return Duration.ofSeconds(settings.getInt("quoteDeadlineSeconds", 300));
    }

    public BidPolicy bidPolicy() {
        String v = settings.getString("bidPolicy", BidPolicy.all.name());
        return BidPolicy.missing_only.name().equalsIgnoreCase(v) ? BidPolicy.missing_only : BidPolicy.all;
    }

    /**
     * The user's switch "answer live mails automatically" (setting {@code autopilot}, default off). When off, quotes
     * are written to {@code draft} for approval, exactly like a session that needs a human.
     */
    public boolean autopilot() {
        return settings.getBool("autopilot", false);
    }

    public boolean portalEnabled() {
        return settings.getBool("portalEnabled", true);
    }

    public boolean bidsEnabled() {
        return settings.getBool("bidsEnabled", true);
    }

    /** Also price the next weight break on the portal: billing at a higher break can be cheaper. */
    public boolean portalProbeNextBreak() {
        return settings.getBool("portalProbeNextBreak", true);
    }

    /** Days a quote stays valid for the customer (setting {@code quoteValidityDays}, default 7). See {@link QuoteSender}. */
    public int quoteValidityDays() {
        return settings.getInt("quoteValidityDays", 7);
    }

    public String ratedeskAddress() {
        return settings.getString("ratedeskAddress", RATEDESK_ADDRESS);
    }
}
