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
                .post(RequestBody.create(gson.toJson(requestBody), MediaType.get("application/json")))
                .addHeader("Content-Type", "application/json")
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code: " + response);
            }
            
            JsonObject jsonResponse = gson.fromJson(response.body().string(), JsonObject.class);
            JsonArray candidates = jsonResponse.getAsJsonArray("candidates");
            if (candidates.size() > 0) {
                JsonObject candidate = candidates.get(0).getAsJsonObject();
                JsonObject contentObj = candidate.getAsJsonObject("content");
                JsonArray responseParts = contentObj.getAsJsonArray("parts");
                if (responseParts.size() > 0) {
                    JsonObject textPart = responseParts.get(0).getAsJsonObject();
                    return textPart.get("text").getAsString();
                }
            }
            throw new IOException("No response from Gemini");
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

