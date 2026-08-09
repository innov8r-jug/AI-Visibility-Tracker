package com.writesonic.visibility.service.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;

/**
 * Several providers (Groq, Cerebras, and any future OpenAI-compatible endpoint) speak the
 * same "chat completions" wire protocol: {model, messages:[{role,content}]} in, and
 * {choices:[{message:{content}}]} out. This is the one place that protocol is encoded, so
 * adding another OpenAI-compatible provider never means re-copying this JSON shape.
 */
public final class OpenAiCompatibleChatClient {

    private OpenAiCompatibleChatClient() {
    }

    public static Request buildRequest(String url, String apiKey, String model, String prompt, Gson gson) {
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

        return new Request.Builder()
                .url(url)
                .post(RequestBody.create(gson.toJson(requestBody), MediaType.get("application/json")))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();
    }

    public static String extractContent(JsonObject jsonResponse, String providerTag) throws IOException {
        if (jsonResponse.has("error")) {
            JsonElement errorEl = jsonResponse.get("error");
            String message = errorEl.isJsonObject() && errorEl.getAsJsonObject().has("message")
                    ? errorEl.getAsJsonObject().get("message").getAsString()
                    : errorEl.toString();
            throw new IOException(providerTag + " API error: " + message);
        }

        JsonArray choices = jsonResponse.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("No choices in " + providerTag + " response");
        }

        JsonObject messageObj = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (messageObj == null || !messageObj.has("content")) {
            throw new IOException("No message content in " + providerTag + " response");
        }
        return messageObj.get("content").getAsString();
    }
}
