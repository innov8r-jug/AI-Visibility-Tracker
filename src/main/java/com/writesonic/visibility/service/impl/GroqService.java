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
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroqService implements AIService {

    private final AIConfig aiConfig;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String query(String prompt, String category) throws Exception {
        log.info("Querying Groq API for category: {}", category);
        log.debug("Prompt: {}", prompt);

        if (!isAvailable()) {
            log.error("Groq API key not configured");
            throw new IllegalStateException("Groq API key not configured");
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "llama-3.1-70b-versatile");
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
                .post(RequestBody.create(gson.toJson(requestBody), MediaType.get("application/json")))
                .addHeader("Authorization", "Bearer " + aiConfig.getGroqApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        log.debug("Sending request to Groq API: {}", aiConfig.getGroqApiUrl());

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body";
                log.error("Groq API error - Code: {}, Body: {}", response.code(), errorBody);
                throw new IOException("Groq API error - Code: " + response.code() + ", Body: " + errorBody);
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                log.error("Empty response body from Groq");
                throw new IOException("Empty response body from Groq");
            }

            String responseString = responseBody.string();
            JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

            // Check for errors in response
            if (jsonResponse.has("error")) {
                JsonObject error = jsonResponse.getAsJsonObject("error");
                String errorMessage = error.has("message") ? error.get("message").getAsString() : "Unknown error";
                log.error("Groq API error: {}", errorMessage);
                throw new IOException("Groq API error: " + errorMessage);
            }

            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                log.error("No choices in Groq response");
                throw new IOException("No choices in Groq response");
            }

            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject messageObj = choice.getAsJsonObject("message");
            if (messageObj == null) {
                log.error("No message in Groq choice");
                throw new IOException("No message in Groq choice");
            }

            if (!messageObj.has("content")) {
                log.error("No content in Groq message");
                throw new IOException("No content in Groq message");
            }

            String responseText = messageObj.get("content").getAsString();
            log.info("Successfully received response from Groq API (length: {} chars)", responseText.length());
            log.debug("Groq response: {}", responseText.substring(0, Math.min(200, responseText.length())));

            return responseText;
        }
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
        // TODO: Implement citation extraction from Groq response
        return citations;
    }
}

