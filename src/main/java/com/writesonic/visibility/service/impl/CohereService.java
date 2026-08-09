package com.writesonic.visibility.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.writesonic.visibility.config.AIConfig;
import com.writesonic.visibility.model.AIModel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Cohere's Chat API v1 (single top-level "message" string field, top-level "text" in the
 * response) has been retired for current models - command-a-plus-05-2026 and friends
 * require v2 ("messages" array of {role, content}, answer nested under
 * message.content[0].text as an array of typed content blocks). This is a manual HTTP
 * call (no Cohere SDK dependency in the project), matching the same approach used for
 * Gemini/Cerebras.
 */
@Service
public class CohereService extends AbstractAIService {

    public CohereService(AIConfig aiConfig, OkHttpClient httpClient) {
        super(aiConfig, httpClient);
    }

    @Override
    protected Request buildRequest(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", aiConfig.getCohereApiModel());

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);
        requestBody.add("messages", messages);

        return new Request.Builder()
                .url(aiConfig.getCohereApiUrl())
                .post(RequestBody.create(gson.toJson(requestBody), MediaType.get("application/json")))
                .addHeader("Authorization", "Bearer " + aiConfig.getCohereApiKey())
                .addHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    protected String parseResponseText(String rawJson) throws IOException {
        JsonObject jsonResponse = gson.fromJson(rawJson, JsonObject.class);

        JsonObject messageObj = jsonResponse.getAsJsonObject("message");
        if (messageObj == null) {
            throw new IOException("No 'message' object in Cohere v2 response");
        }

        JsonArray contentBlocks = messageObj.getAsJsonArray("content");
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            throw new IOException("No content blocks in Cohere v2 response message");
        }

        StringBuilder text = new StringBuilder();
        for (JsonElement el : contentBlocks) {
            JsonObject block = el.getAsJsonObject();
            if (block.has("text")) {
                text.append(block.get("text").getAsString());
            }
        }

        if (text.length() == 0) {
            throw new IOException("No text content blocks in Cohere v2 response");
        }
        return text.toString();
    }

    @Override
    protected String getApiKey() {
        return aiConfig.getCohereApiKey();
    }

    @Override
    public String getModelName() {
        return "Cohere AI";
    }

    @Override
    public AIModel getModelType() {
        return AIModel.COHERE;
    }
}
