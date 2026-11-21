package ai.aeiou.forwarder.session;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface SessionService {

    /** Creates a session with a unique public_ref like "AEI-7K2Q9". Writes a session.created event. */
    long create(String type, String customerAddr, String subject, OffsetDateTime deadlineAt);

    Optional<SessionView> find(long sessionId);

    Optional<SessionView> findByPublicRef(String publicRef);

    void setStatus(long sessionId, SessionStatus status);

    void setMode(long sessionId, String mode);

    /** Flags the session for a human (shown on top of the control panel) and writes a human.needed event. */
    void flagNeedsHuman(long sessionId, String reason);

    void clearNeedsHuman(long sessionId);

    /**
     * Atomically claims the single quote of this session:
     * {@code UPDATE session SET quoted_at=now(), ... WHERE id=? AND quoted_at IS NULL}.
     * Returns false if a quote was already sent; the caller must then NOT send.
     */
    boolean claimQuote(long sessionId, BigDecimal total, String currency, BigDecimal totalEur);

    /** Reverts a claim if sending failed. */
    void releaseQuote(long sessionId);

    void markBooked(long sessionId);

    record SessionView(long id, String publicRef, String type, SessionStatus status, String customerAddr, String subject,
                       String mode, boolean needsHuman, boolean autopilot, OffsetDateTime deadlineAt,
                       OffsetDateTime quotedAt, BigDecimal quotedTotal, String quotedCurrency, boolean booked,
                       OffsetDateTime createdAt) {}
}
