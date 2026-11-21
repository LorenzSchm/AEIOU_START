package ai.aeiou.forwarder.settings;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.CoreDatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Every setting change leaves exactly one {@code setting.changed} event, with the right actor. */
class SettingsEventTest extends CoreDatabaseTest {

    @Autowired SettingsService settings;
    @Autowired JdbcClient jdbc;

    @Test
    void oneEventPerChangeWithHumanActorWhenUpdatedBySomeone() {
        long before = maxEventId();

        settings.set("quoteValidityDays", 10, "human");
        settings.set("quoteValidityDays", 7, null);

        assertThat(settings.getInt("quoteValidityDays", 0)).isEqualTo(7);
        assertThat(jdbc.sql("SELECT kind || ':' || actor FROM event WHERE id > ? AND payload->>'key' = 'quoteValidityDays' ORDER BY id")
                .param(before).query(String.class).list())
                .containsExactly("setting.changed:human", "setting.changed:system");
    }

    private long maxEventId() {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM event").query(Long.class).single();
    }
}
