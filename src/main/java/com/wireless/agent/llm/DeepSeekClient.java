package com.wireless.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class DeepSeekClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final String apiBase;
    private final String apiKey;
    private final String model;

    public DeepSeekClient(String apiBase, String apiKey, String model) {
        this.apiBase = apiBase;
        this.apiKey = apiKey;
        this.model = model;
    }

    public DeepSeekClient() {
        this(
            System.getenv().getOrDefault("DEEPSEEK_API_BASE", ""),
            System.getenv().getOrDefault("DEEPSEEK_API_KEY", ""),
            System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat")
        );
    }

    public String apiBase() { return apiBase; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }

    /** Create a default OkHttpClient (used in production path). */
    public static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public String chat(List<Map<String, String>> messages) {
        return chat(defaultHttpClient(), messages, 1024, 0.1);
    }

    public String chat(OkHttpClient httpClient, List<Map<String, String>> messages) {
        return chat(httpClient, messages, 1024, 0.1);
    }

    @SuppressWarnings("unchecked")
    public String chat(OkHttpClient httpClient, List<Map<String, String>> messages,
                       int maxTokens, double temperature) {
        try {
            var body = Map.of(
                "model", model,
                "messages", messages,
                "max_tokens", maxTokens,
                "temperature", temperature
            );
            var json = MAPPER.writeValueAsString(body);
            var request = new Request.Builder()
                    .url(apiBase.replaceAll("/+$", "") + "/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    var errBody = response.body() != null
                            ? response.body().string() : response.message();
                    return "[ERROR] HTTP " + response.code() + ": " + errBody;
                }
                var respBody = MAPPER.readValue(response.body().string(), Map.class);
                var choices = (List<Map<String, Object>>) respBody.get("choices");
                if (choices == null || choices.isEmpty()) {
                    return "[ERROR] Empty choices in response: " + respBody;
                }
                var message = (Map<String, String>) choices.get(0).get("message");
                if (message == null) {
                    return "[ERROR] Missing message in response: " + choices.get(0);
                }
                var content = message.get("content");
                if (content == null) {
                    return "[ERROR] Missing content in response: " + message;
                }
                return content;
            }
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeepSeekClient that)) return false;
        return Objects.equals(apiBase, that.apiBase)
                && Objects.equals(apiKey, that.apiKey)
                && Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiBase, apiKey, model);
    }

    @Override
    public String toString() {
        return "DeepSeekClient[apiBase=" + apiBase + ", apiKey=" + apiKey + ", model=" + model + "]";
    }
}
