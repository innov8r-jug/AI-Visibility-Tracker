package com.writesonic.visibility.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.writesonic.visibility.config.AIConfig;
import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Citation;
import com.writesonic.visibility.service.AIService;
import com.writesonic.visibility.service.dto.AIQueryResult;
import com.writesonic.visibility.service.util.CitationTextExtractor;
import com.writesonic.visibility.service.util.OpenAiCompatibleChatClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Does NOT extend AbstractAIService: unlike the other providers, Groq needs a
 * multi-model fallback retry loop (some free-tier models get decommissioned without
 * notice) - a genuinely different control flow, not just a different request/response
 * shape. It still reuses the shared OpenAiCompatibleChatClient protocol helpers and
 * CitationTextExtractor rather than duplicating that logic.
 */
@Service
@Slf4j
public class GroqService implements AIService {

    private final AIConfig aiConfig;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    private static final List<String> GROQ_MODELS = List.of(
            "llama-3.3-70b-versatile",
            "qwen/qwen3-32b",
            "llama-3.1-8b-instant"
    );

    public GroqService(AIConfig aiConfig, OkHttpClient httpClient) {
        this.aiConfig = aiConfig;
        this.httpClient = httpClient;
    }

    @Override
    public AIQueryResult query(String prompt, String category) throws Exception {
        String threadName = Thread.currentThread().getName();
        long startTime = System.currentTimeMillis();

        log.info("[GROQ] [THREAD: {}] Starting query for category: {}", threadName, category);

        if (!isAvailable()) {
            throw new IllegalStateException("Groq API key not configured");
        }

        IOException lastException = null;

        for (String model : GROQ_MODELS) {
            log.info("[GROQ] [THREAD: {}] Trying model: {}", threadName, model);

            try {
                String text = executeGroqRequest(model, prompt, threadName, startTime);
                // Groq's plain chat-completions models have no web-search/grounding
                // capability, so there's never a real research trail to report here.
                return AIQueryResult.builder().text(text).groundedCitations(List.of()).build();
            } catch (IOException e) {
                lastException = e;

                if (isModelDecommissioned(e)) {
                    log.warn("[GROQ] [THREAD: {}] Model {} decommissioned. Falling back...",
                            threadName, model);
                    continue;
                }

                throw e;
            }
        }

        throw new IOException("All Groq models failed", lastException);
    }

    private String executeGroqRequest(String model, String prompt, String threadName, long startTime) throws IOException {
        Request request = OpenAiCompatibleChatClient.buildRequest(aiConfig.getGroqApiUrl(), aiConfig.getGroqApiKey(), model, prompt, gson);

        try (Response response = httpClient.newCall(request).execute()) {
            String responseString = response.body() != null ? response.body().string() : null;

            if (!response.isSuccessful()) {
                throw new IOException("Groq API error - Code: " + response.code() + ", Body: " + responseString);
            }
            if (responseString == null || responseString.isEmpty()) {
                throw new IOException("Empty response body from Groq");
            }

            JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);
            String responseText = OpenAiCompatibleChatClient.extractContent(jsonResponse, "Groq");

            log.info("[GROQ] [THREAD: {}] ✓ Model {} succeeded ({} chars, {} ms)",
                    threadName, model, responseText.length(), System.currentTimeMillis() - startTime);

            return responseText;
        }
    }

    private boolean isModelDecommissioned(IOException e) {
        return e.getMessage() != null && e.getMessage().toLowerCase().contains("decommissioned");
    }

    @Override
    public String getModelName() {
        return "Groq AI";
    }

    @Override
    public AIModel getModelType() {
        return AIModel.GROQ;
    }

    @Override
    public boolean isAvailable() {
        return aiConfig.getGroqApiKey() != null && !aiConfig.getGroqApiKey().isEmpty();
    }

    @Override
    public List<Citation> extractCitations(String response) {
        List<Citation> citations = CitationTextExtractor.extract(response);
        log.info("Extracted {} unique citation(s) from Groq response", citations.size());
        return citations;
    }
}
