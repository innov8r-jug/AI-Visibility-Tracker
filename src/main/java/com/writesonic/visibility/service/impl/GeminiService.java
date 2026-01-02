package com.writesonic.visibility.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.writesonic.visibility.config.AIConfig;
import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Citation;
import com.writesonic.visibility.service.AIService;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GeminiService implements AIService {
    
    private final AIConfig aiConfig;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String query(String prompt, String category) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Google API key not configured");
        }

        JsonObject requestBody = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();

        part.addProperty("text", prompt);
        parts.add(part);
        content.add("parts", parts);
        contents.add(content);
        requestBody.add("contents", contents);

        String url = aiConfig.getGoogleApiUrl() + "?key=" + aiConfig.getGoogleApiKey();

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        gson.toJson(requestBody),
                        MediaType.get("application/json")
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String responseString = responseBody != null ? responseBody.string() : null;

            if (!response.isSuccessful()) {
                throw new IOException("Gemini API error - Code: " + response.code() + ", Body: " + responseString);
            }

            if (responseString == null || responseString.isEmpty()) {
                throw new IOException("Empty response body from Gemini");
            }

            JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

            if (jsonResponse.has("error")) {
                JsonObject error = jsonResponse.getAsJsonObject("error");
                String errorMessage = error.has("message") ? error.get("message").getAsString() : "Unknown error";
                throw new IOException("Gemini API error: " + errorMessage);
            }

            JsonArray candidates = jsonResponse.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new IOException("No candidates in Gemini response");
            }

            JsonObject candidate = candidates.get(0).getAsJsonObject();
            JsonObject contentObj = candidate.getAsJsonObject("content");
            if (contentObj == null) {
                throw new IOException("No content in Gemini candidate");
            }

            JsonArray responseParts = contentObj.getAsJsonArray("parts");
            if (responseParts == null || responseParts.isEmpty()) {
                throw new IOException("No parts in Gemini content");
            }

            JsonObject textPart = responseParts.get(0).getAsJsonObject();
            if (!textPart.has("text")) {
                throw new IOException("No text in Gemini response part");
            }

            return textPart.get("text").getAsString();
        } catch (Exception e) {
            throw e;
        }
    }


    @Override
    public String getModelName() {
        return "Google Gemini";
    }
    
    @Override
    public AIModel getModelType() {
        return AIModel.GEMINI;
    }
    
    @Override
    public boolean isAvailable() {
        return aiConfig.getGoogleApiKey() != null && !aiConfig.getGoogleApiKey().isEmpty();
    }
    
    @Override
    public List<Citation> extractCitations(String response) {
        List<Citation> citations = new ArrayList<>();
        // TODO: Implement citation extraction from Gemini response
        return citations;
    }
}

