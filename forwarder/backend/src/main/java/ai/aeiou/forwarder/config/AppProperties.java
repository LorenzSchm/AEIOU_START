package ai.aeiou.forwarder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed view of the {@code app.*} block in application.yml.
 *
 * <p>Poller and real sending default to off: only one running instance may poll and send,
 * otherwise customers receive duplicate mails.
 */
@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue FiveU fiveu,
        @DefaultValue Poller poller,
        @DefaultValue Mail mail,
        @DefaultValue Llm llm,
        @DefaultValue("../../start-kit") String startKitDir) {

    /** 5U API access. The same team key authenticates mailbox, portal, gateway and board. */
    public record FiveU(
            @DefaultValue("https://api.start-hack.5u.ai") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("aeiou@hackloop.mail") String mailboxAddress) {}

    public record Poller(@DefaultValue("false") boolean enabled, @DefaultValue("1000") long intervalMs) {}

    /**
     * {@code sendEnabled=false} turns every outgoing mail into a stored dry run.
     * {@code customerRepliesEnabled=false} still sends to carriers and the rate desk, but turns every mail to anyone
     * else (customers, tracking@) into a dry run: test the whole flow against the real API without answering the
     * scored requests.
     */
    public record Mail(@DefaultValue("false") boolean sendEnabled, @DefaultValue("false") boolean customerRepliesEnabled) {}

    /**
     * {@code baseUrl}/{@code apiKey} empty: the 5U gateway ({fiveu.base-url}/v1/gateway with the team key), which is
     * where scored LLM cost is counted. Set both (e.g. https://api.openai.com/v1 + an own key) to test without
     * touching the team's gateway spend.
     */
    public record Llm(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("gpt-4o-mini") String modelRouter,
            @DefaultValue("gpt-4o-mini") String modelWriter,
            @DefaultValue("gpt-4o-mini") String modelExtract) {}
}
