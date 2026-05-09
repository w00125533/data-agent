package com.wireless.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeepSeekClientTest {

    @Mock
    private OkHttpClient httpClient;

    @Mock
    private Call call;

    @Test
    void shouldInitFromEnvOrDefaults() {
        var client = new DeepSeekClient(
                "https://api.deepseek.com/v1",
                "sk-test",
                "deepseek-chat"
        );
        assertThat(client.model()).isEqualTo("deepseek-chat");
        assertThat(client.apiBase()).isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendCorrectPayload() throws Exception {
        var client = new DeepSeekClient(
                "https://api.deepseek.com/v1",
                "sk-test",
                "deepseek-chat"
        );

        var respBody = Map.of(
                "choices", List.of(
                        Map.of("message", Map.of("content", "PONG"))
                )
        );
        var respJson = new ObjectMapper().writeValueAsString(respBody);
        var response = new Response.Builder()
                .request(new Request.Builder().url("https://api.deepseek.com/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(respJson, MediaType.parse("application/json")))
                .build();

        var captor = ArgumentCaptor.forClass(Request.class);
        when(httpClient.newCall(captor.capture())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        var messages = List.of(Map.of("role", "user", "content", "PING"));
        var result = client.chat(httpClient, messages);
        assertThat(result).isEqualTo("PONG");

        // Verify the request was built correctly
        Request captured = captor.getValue();
        assertThat(captured.url().toString()).isEqualTo("https://api.deepseek.com/v1/chat/completions");
        assertThat(captured.header("Authorization")).isEqualTo("Bearer sk-test");
    }

    @Test
    void shouldReturnErrorPrefixOnFailure() throws Exception {
        var client = new DeepSeekClient(
                "https://api.deepseek.com/v1",
                "sk-test",
                "deepseek-chat"
        );

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("Connection refused"));

        var messages = List.of(Map.of("role", "user", "content", "hi"));
        var result = client.chat(httpClient, messages);
        assertThat(result).startsWith("[ERROR]");
    }
}
