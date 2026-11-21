package ai.aeiou.forwarder.application;

import ai.aeiou.forwarder.infrastructure.Ledger;
import java.time.*;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;

@Component
public class TimelineClock {
  public static final Instant START = Instant.parse("2026-11-21T15:00:00Z");
  private final Ledger store;
  private final Clock clock;
  public final boolean live;

  public TimelineClock(Ledger store, Clock clock, Environment env) {
    this.store = store;
    this.clock = clock;
    live = env.acceptsProfiles(Profiles.of("live"));
  }

  public Instant now() {
    return live
        ? clock.instant()
        : START.plusSeconds(
            store.db.queryForObject(
                "select demo_seconds from stream_clock where id=1", Long.class));
  }
}
