package ai.aeiou.forwarder.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import ai.aeiou.forwarder.CoreDatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Sending is on but customer replies are off: carriers and the rate desk get real mail, everyone else
 * (customers, tracking@) only a dry run. This is the mode for testing against the real API without
 * answering the scored requests.
 */
@TestPropertySource(properties = {"app.mail.send-enabled=true", "app.mail.customer-replies-enabled=false"})
class CustomerReplyGuardTest extends CoreDatabaseTest {

    @Autowired
    MailGateway gateway;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void carrier() {
        reset(fiveU);
        jdbc.sql("""
                INSERT INTO carrier (name, kind, email, serves) VALUES ('Guard Test Air', 'airline', 'guard-air@hackloop.mail', '{SIN}')
                ON CONFLICT (email) DO NOTHING""").update();
    }

    @Test
    void customerMailIsOnlyADryRun() {
        StoredMail stored = gateway.send(null, OutgoingMail.fresh("buyer@customer.example", "Re: RFQ", "Quote"));

        assertThat(stored.externalId()).isNegative();
        verify(fiveU, never()).send(any());
    }

    @Test
    void trackingDeskIsOnlyADryRun() {
        StoredMail stored = gateway.send(null, OutgoingMail.fresh("tracking@hackloop.mail", "Re: where is it", "On its way"));

        assertThat(stored.externalId()).isNegative();
        verify(fiveU, never()).send(any());
    }

    @Test
    void carriersAndRateDeskReceiveRealMail() {
        for (String to : new String[] {"guard-air@hackloop.mail", "Ratedesk@hackloop.mail"}) {
            try {
                gateway.send(null, OutgoingMail.fresh(to, "Rate request", "Please quote"));
            } catch (RuntimeException expected) {
                // the mocked client returns no response; we only care that a real send was attempted
            }
        }
        verify(fiveU, org.mockito.Mockito.times(2)).send(any());
    }
}
