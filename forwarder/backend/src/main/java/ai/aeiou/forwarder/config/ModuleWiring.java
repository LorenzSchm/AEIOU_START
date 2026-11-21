package ai.aeiou.forwarder.config;

import ai.aeiou.forwarder.api.ApiException;
import ai.aeiou.forwarder.api.port.DraftSender;
import ai.aeiou.forwarder.api.port.MailRerouter;
import ai.aeiou.forwarder.api.port.TariffActions;
import ai.aeiou.forwarder.rfq.QuoteSender;
import ai.aeiou.forwarder.router.Router;
import ai.aeiou.forwarder.tariff.TariffAdminService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Connects the REST API's ports to the modules that implement them. */
@Configuration(proxyBeanMethods = false)
public class ModuleWiring {

    @Bean
    DraftSender draftSender(QuoteSender quotes) {
        return (sessionId, overrideBody) -> {
            switch (quotes.sendDraft(sessionId, overrideBody)) {
                case SENT, DRAFTED -> { }
                case ALREADY_QUOTED -> throw ApiException.conflict("already_quoted",
                        "This thread already has a quote; only the first one counts");
                case NO_DRAFT -> throw ApiException.conflict("no_draft", "There is no pending draft for this session");
                case FAILED -> throw ApiException.conflict("send_failed", "Sending failed, see the session timeline");
            }
        };
    }

    @Bean
    MailRerouter mailRerouter(Router router) {
        return router::reroute;
    }

    @Bean
    TariffActions tariffActions(TariffAdminService admin) {
        return new TariffActions() {
            @Override
            public void activate(long tariffId) {
                admin.activate(tariffId);
            }

            @Override
            public void reject(long tariffId) {
                admin.reject(tariffId);
            }
        };
    }
}
