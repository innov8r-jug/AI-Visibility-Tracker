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

        if (!isAvailable()) {
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

        String url = aiConfig.getGroqApiUrl();
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException("Invalid Groq API URL");
        }

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        gson.toJson(requestBody),
                        MediaType.get("application/json")
                ))
                .addHeader("Authorization", "Bearer " + aiConfig.getGroqApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {

            ResponseBody responseBody = response.body();
            String responseString = responseBody != null ? responseBody.string() : null;

            if (!response.isSuccessful()) {
                log.error("Groq API error - Code: {}, Body: {}", response.code(), responseString);
                throw new IOException("Groq API error - Code: " + response.code());
            }

            JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

            if (jsonResponse.has("error")) {
                throw new IOException(
                        jsonResponse.getAsJsonObject("error")
                                .get("message").getAsString()
                );
            }

            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("No choices in Groq response");
            }

            return choices.get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content")
                    .getAsString();
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

