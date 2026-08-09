package com.writesonic.visibility.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.writesonic.visibility.config.AIConfig;
import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Citation;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class GeminiService extends AbstractAIService {

    public GeminiService(AIConfig aiConfig, OkHttpClient httpClient) {
        super(aiConfig, httpClient);
    }

    @Override
    protected Request buildRequest(String prompt) {
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

        // Grounding with Google Search: lets Gemini actually search the web while
        // answering, and return the real pages it consulted via groundingMetadata below -
        // this is what makes citations a genuine research trail instead of a URL the
        // model happened to type from memory (which is all the regex fallback can offer).
        JsonArray tools = new JsonArray();
        JsonObject googleSearchTool = new JsonObject();
        googleSearchTool.add("google_search", new JsonObject());
        tools.add(googleSearchTool);
        requestBody.add("tools", tools);

        String url = aiConfig.getGoogleApiUrl() + "?key=" + aiConfig.getGoogleApiKey();
        return new Request.Builder()
                .url(url)
                .post(RequestBody.create(gson.toJson(requestBody), MediaType.get("application/json")))
                .build();
    }

    @Override
    protected String parseResponseText(String rawJson) throws IOException {
        JsonObject jsonResponse = gson.fromJson(rawJson, JsonObject.class);

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
    }

    @Override
    protected String getApiKey() {
        return aiConfig.getGoogleApiKey();
    }

    /**
     * Reads candidates[0].groundingMetadata.groundingChunks[].web.{uri,title} - the
     * actual list of pages Gemini searched and consulted while forming its answer, when
     * the google_search tool (added in buildRequest) causes a search to happen. Not
     * every prompt triggers a search, so this can legitimately be empty; when it is, the
     * caller falls back to scraping URLs out of the answer text.
     */
    @Override
    protected List<Citation> parseGroundedCitations(String rawJson) {
        List<Citation> citations = new ArrayList<>();
        try {
            JsonObject jsonResponse = gson.fromJson(rawJson, JsonObject.class);
            JsonArray candidates = jsonResponse.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) return citations;

            JsonObject candidate = candidates.get(0).getAsJsonObject();
            JsonObject groundingMetadata = candidate.getAsJsonObject("groundingMetadata");
            if (groundingMetadata == null) return citations;

            JsonArray chunks = groundingMetadata.getAsJsonArray("groundingChunks");
            if (chunks == null) return citations;

            Set<String> seenUrls = new HashSet<>();
            for (JsonElement el : chunks) {
                JsonObject chunk = el.getAsJsonObject();
                JsonObject web = chunk.getAsJsonObject("web");
                if (web == null || !web.has("uri")) continue;

                String uri = web.get("uri").getAsString();
                if (!seenUrls.add(uri)) continue;

                Citation citation = new Citation();
                citation.setSourceUrl(uri);
                citation.setSourceTitle(web.has("title") ? web.get("title").getAsString() : null);
                citations.add(citation);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Gemini groundingMetadata - falling back to text-scraped citations", e);
        }
        return citations;
    }

    @Override
    public String getModelName() {
        return "Google Gemini";
    }

    @Override
    public AIModel getModelType() {
        return AIModel.GEMINI;
    }
}
