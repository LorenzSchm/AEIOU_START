package ai.aeiou.forwarder.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Core infrastructure: properties, scheduling, HTTP clients for the 5U API and the executor
 * that routes incoming mails on virtual threads.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class CoreConfig {

    /** Qualifier of the RestClient for mailbox, portal and board ({@code X-API-Key} auth). */
    public static final String FIVEU_REST_CLIENT = "fiveuRestClient";
    /** Qualifier of the RestClient for the LLM gateway (Bearer auth, long read timeout). */
    public static final String LLM_REST_CLIENT = "llmRestClient";
    /** Qualifier of the executor that runs one virtual thread per routed mail. */
    public static final String MAIL_ROUTING_EXECUTOR = "mailRoutingExecutor";

    @Bean(FIVEU_REST_CLIENT)
    RestClient fiveuRestClient(AppProperties props) {
        return RestClient.builder()
                .baseUrl(props.fiveu().baseUrl())
                .requestFactory(requestFactory(Duration.ofSeconds(20)))
                .defaultHeader("X-API-Key", props.fiveu().apiKey())
                .build();
    }

    @Bean(LLM_REST_CLIENT)
    RestClient llmRestClient(AppProperties props) {
        return RestClient.builder()
                .baseUrl(props.fiveu().baseUrl())
                .requestFactory(requestFactory(Duration.ofSeconds(90)))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.fiveu().apiKey())
                .build();
    }

    @Bean(name = MAIL_ROUTING_EXECUTOR, destroyMethod = "close")
    ExecutorService mailRoutingExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("route-", 0).factory());
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
