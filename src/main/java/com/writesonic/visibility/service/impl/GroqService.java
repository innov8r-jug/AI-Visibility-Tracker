package com.writesonic.visibility.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Does NOT extend AbstractAIService: unlike the other providers, Groq needs a
 * multi-model fallback retry loop (some free-tier models get decommissioned without
 * notice) - a genuinely different control flow, not just a different request/response
 * shape. It still reuses the shared OpenAiCompatibleChatClient protocol helpers and
 * CitationTextExtractor rather than duplicating that logic.
 * <p>
 * groq/compound and groq/compound-mini run Groq's own agentic system with built-in web
 * search - the search happens server-side in a single request/response, and the actual
 * searched sources come back in message.executed_tools[].search_results[]. Tried first
 * (compound can make multiple tool calls; compound-mini is faster, single tool call);
 * the plain chat models remain as a fallback if compound is unavailable, just without
 * a real research trail (they have no search capability at all).
 */
@Service
@Slf4j
public class GroqService implements AIService {

    private final AIConfig aiConfig;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    private static final List<String> GROQ_MODELS = List.of(
            "groq/compound",
            "groq/compound-mini",
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
                return executeGroqRequest(model, prompt, threadName, startTime);
            } catch (IOException e) {
                lastException = e;
                // Fall back to the next model regardless of *why* this one failed - not
                // just "decommissioned". groq/compound can fail for compound-specific
                // reasons (e.g. payload-size limits on its preview tier) that have nothing
                // to do with the plain chat models below it in the list, so a narrow
                // decommission-only check would kill the whole fallback chain on the first
                // model-specific hiccup instead of trying the rest.
                log.warn("[GROQ] [THREAD: {}] Model {} failed after {} ms ({}), falling back to next model...",
                        threadName, model, System.currentTimeMillis() - startTime, e.getMessage());
            }
        }

        log.error("[GROQ] [THREAD: {}] ✗ All Groq models failed after {} ms",
                threadName, System.currentTimeMillis() - startTime);
        throw new IOException("All Groq models failed", lastException);
    }

    private AIQueryResult executeGroqRequest(String model, String prompt, String threadName, long startTime) throws IOException {
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
            List<Citation> groundedCitations = parseExecutedToolsCitations(jsonResponse);

            log.info("[GROQ] [THREAD: {}] ✓ Model {} succeeded ({} chars, {} grounded citation(s), {} ms)",
                    threadName, model, responseText.length(), groundedCitations.size(), System.currentTimeMillis() - startTime);

            return AIQueryResult.builder().text(responseText).groundedCitations(groundedCitations).build();
        }
    }

    /**
     * Reads choices[0].message.executed_tools[].search_results[] - the actual pages
     * groq/compound(-mini) searched while forming its answer. Empty for plain chat
     * models (llama/qwen fallback), which have no search capability at all.
     */
    private List<Citation> parseExecutedToolsCitations(JsonObject jsonResponse) {
        List<Citation> citations = new ArrayList<>();
        try {
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return citations;

            JsonObject messageObj = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (messageObj == null) return citations;

            JsonArray executedTools = messageObj.getAsJsonArray("executed_tools");
            if (executedTools == null) return citations;

            Set<String> seenUrls = new HashSet<>();
            for (JsonElement toolEl : executedTools) {
                JsonObject tool = toolEl.getAsJsonObject();
                JsonArray searchResults = extractSearchResultsArray(tool);
                if (searchResults == null) continue;

                for (JsonElement resultEl : searchResults) {
                    if (!resultEl.isJsonObject()) continue;
                    JsonObject result = resultEl.getAsJsonObject();
                    if (!result.has("url")) continue;

                    String url = result.get("url").getAsString();
                    if (!seenUrls.add(url)) continue;

                    Citation citation = new Citation();
                    citation.setSourceUrl(url);
                    citation.setSourceTitle(result.has("title") ? result.get("title").getAsString() : null);
                    citations.add(citation);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Groq executed_tools search results - continuing without grounded citations", e);
        }
        return citations;
    }

    /**
     * "search_results" has been observed as a direct JSON array in some responses and as
     * a wrapper object (e.g. {"results": [...]}) in others - handle both shapes rather
     * than assuming one and crashing with a ClassCastException on the other.
     */
    private JsonArray extractSearchResultsArray(JsonObject tool) {
        JsonElement searchResultsEl = tool.get("search_results");
        if (searchResultsEl == null || searchResultsEl.isJsonNull()) {
            return null;
        }
        if (searchResultsEl.isJsonArray()) {
            return searchResultsEl.getAsJsonArray();
        }
        if (searchResultsEl.isJsonObject()) {
            JsonObject wrapper = searchResultsEl.getAsJsonObject();
            for (String key : new String[]{"results", "search_results", "items"}) {
                if (wrapper.has(key) && wrapper.get(key).isJsonArray()) {
                    return wrapper.getAsJsonArray(key);
                }
            }
        }
        return null;
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
