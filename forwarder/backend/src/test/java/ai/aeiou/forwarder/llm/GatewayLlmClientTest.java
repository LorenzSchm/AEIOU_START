package ai.aeiou.forwarder.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.aeiou.forwarder.llm.LlmClient.ChatMessage;
import ai.aeiou.forwarder.llm.LlmClient.LlmRequest;
import ai.aeiou.forwarder.llm.LlmClient.LlmResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Runs against a local mock server only; never the live gateway. */
class GatewayLlmClientTest {

    private static final String URL = "http://gw.test/v1/gateway/chat/completions";

    record Answer(String city, int count) {}

    private MockRestServiceServer server;
    private LlmCallRecorder recorder;
    private GatewayLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gw.test");
        server = MockRestServiceServer.bindTo(builder).build();
        recorder = mock(LlmCallRecorder.class);
        client = new GatewayLlmClient(builder.build(), JsonMapper.builder().build(), recorder);
    }

    @Test
    void structuredSendsStrictSchemaAndRecordsCost() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required[0]").value("city"))
                .andExpect(jsonPath("$.temperature").value(0.0))
                .andRespond(withSuccess(completion("{\\\"city\\\":\\\"Munich\\\",\\\"count\\\":3}"), MediaType.APPLICATION_JSON));

        LlmResult<Answer> result = client.structured(request(), Answer.class);

        assertThat(result.value()).isEqualTo(new Answer("Munich", 3));
        assertThat(result.promptTokens()).isEqualTo(1000);
        assertThat(result.costUsd()).isEqualTo(ModelPricing.costUsd("gpt-4o-mini", 1000, 200));
        ArgumentCaptor<LlmCallRecorder.Call> call = ArgumentCaptor.forClass(LlmCallRecorder.Call.class);
        verify(recorder).record(call.capture());
        assertThat(call.getValue().success()).isTrue();
        assertThat(call.getValue().sessionId()).isEqualTo(5L);
    }

    @Test
    void retriesOnceAfterAServerError() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL))
                .andRespond(withSuccess(completion("{\\\"city\\\":\\\"Hamburg\\\",\\\"count\\\":1}"), MediaType.APPLICATION_JSON));

        assertThat(client.structured(request(), Answer.class).value().city()).isEqualTo("Hamburg");
        verify(recorder, times(2)).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retriesOnceAfterUnparsableJsonThenFails() {
        server.expect(ExpectedCount.times(2), requestTo(URL))
                .andRespond(withSuccess(completion("not json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.structured(request(), Answer.class)).isInstanceOf(LlmException.class);
        server.verify();
    }

    @Test
    void doesNotRetryClientErrors() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("bad"));

        assertThatThrownBy(() -> client.text(request())).isInstanceOf(LlmException.class).hasMessageContaining("400");
        server.verify();
    }

    private static LlmRequest request() {
        return new LlmRequest(5L, "test", "gpt-4o-mini", List.of(ChatMessage.user("hi")), 0.0);
    }

    private static String completion(String escapedContent) {
        return """
                {"model": "gpt-4o-mini-2024-07-18",
                 "choices": [{"message": {"role": "assistant", "content": "%s"}}],
                 "usage": {"prompt_tokens": 1000, "completion_tokens": 200}}""".formatted(escapedContent);
    }
}
