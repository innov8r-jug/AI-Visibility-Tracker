package com.writesonic.visibility.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.writesonic.visibility.config.AIConfig;
import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Citation;
import com.writesonic.visibility.service.AIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
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

    @Override
    public String query(String prompt, String category) throws Exception {
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
                return executeGroqRequest(
                        model,
                        prompt,
                        threadName,
                        startTime
                );
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

    private String executeGroqRequest(
            String model,
            String prompt,
            String threadName,
            long startTime
    ) throws IOException {

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("temperature", 0.7);
        requestBody.addProperty("max_tokens", 2000);

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);
        requestBody.add("messages", messages);

        Request request = new Request.Builder()
                .url(aiConfig.getGroqApiUrl())
                .post(RequestBody.create(
                        gson.toJson(requestBody),
                        MediaType.get("application/json")
                ))
                .addHeader("Authorization", "Bearer " + aiConfig.getGroqApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {

            String responseString = response.body() != null
                    ? response.body().string()
                    : null;

            if (!response.isSuccessful()) {
                throw new IOException("Groq API error - Code: "
                        + response.code() + ", Body: " + responseString);
            }

            JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

            if (jsonResponse.has("error")) {
                throw new IOException(jsonResponse
                        .getAsJsonObject("error")
                        .get("message")
                        .getAsString());
            }

            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("No choices in Groq response");
            }

            JsonObject messageObj = choices
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("message");

            String responseText = messageObj.get("content").getAsString();

            log.info("[GROQ] [THREAD: {}] ✓ Model {} succeeded ({} chars, {} ms)",
                    threadName,
                    model,
                    responseText.length(),
                    System.currentTimeMillis() - startTime);

            return responseText;
        }
    }

    private boolean isModelDecommissioned(IOException e) {
        return e.getMessage() != null &&
                e.getMessage().toLowerCase().contains("decommissioned");
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
        List<Citation> citations = new ArrayList<>();

        if (response == null || response.isEmpty()) {
            return citations;
        }

        String urlPattern = "(?i)\\b(https?://[^\\s<>\"'{}|\\\\^`\\[\\]]+)|(www\\.[^\\s<>\"'{}|\\\\^`\\[\\]]+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(urlPattern);
        java.util.regex.Matcher matcher = pattern.matcher(response);

        Set<String> foundUrls = new HashSet<>();

        while (matcher.find()) {
            String url = matcher.group(0).replaceAll("[.,;:!?]+$", "");

            if (url.startsWith("www.")) {
                url = "https://" + url;
            }

            if (!foundUrls.add(url)) continue;

            try {
                new java.net.URL(url);
            } catch (Exception e) {
                continue;
            }

            Citation citation = new Citation();
            citation.setSourceUrl(url);
            citation.setSourceTitle(extractDomainName(url));
            citations.add(citation);
        }

        return citations;
    }

    private String extractDomainName(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return url;
        }
    }
}
