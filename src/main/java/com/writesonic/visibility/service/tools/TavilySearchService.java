package com.writesonic.visibility.service.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.writesonic.visibility.config.AIConfig;
import com.writesonic.visibility.service.dto.TavilySearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around Tavily's search API, used as the "web_search" tool backing
 * CohereService's tool-calling loop. Uses the same OkHttpClient/Gson stack as every
 * other provider in this project (no new HTTP client library introduced).
 * <p>
 * Deliberately never throws: a web-search failure should degrade the calling model's
 * answer to "no grounding this round", not take down the whole Cohere call - matches
 * the project's existing failure-isolation principle (one provider/step failing
 * doesn't cascade into breaking others).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TavilySearchService {

    private final AIConfig aiConfig;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    public List<TavilySearchResult> search(String query) {
        if (aiConfig.getTavilyApiKey() == null || aiConfig.getTavilyApiKey().isEmpty()) {
            log.warn("Tavily API key not configured - skipping web search for query: '{}'", query);
            return List.of();
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("api_key", aiConfig.getTavilyApiKey());
        requestBody.addProperty("query", query);
        requestBody.addProperty("search_depth", "basic");
        requestBody.addProperty("max_results", 5);

        Request request = new Request.Builder()
                .url(aiConfig.getTavilyApiUrl())
                .post(RequestBody.create(gson.toJson(requestBody), MediaType.get("application/json")))
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseString = response.body() != null ? response.body().string() : null;

            if (!response.isSuccessful() || responseString == null) {
                log.warn("Tavily search failed for query '{}' - Code: {}, Body: {}", query, response.code(), responseString);
                return List.of();
            }

            JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);
            JsonArray results = jsonResponse.getAsJsonArray("results");
            if (results == null) {
                return List.of();
            }

            List<TavilySearchResult> parsed = new ArrayList<>();
            for (JsonElement el : results) {
                JsonObject result = el.getAsJsonObject();
                parsed.add(new TavilySearchResult(
                        result.has("title") ? result.get("title").getAsString() : null,
                        result.has("url") ? result.get("url").getAsString() : null,
                        result.has("content") ? result.get("content").getAsString() : null
                ));
            }
            log.info("Tavily returned {} result(s) for query: '{}'", parsed.size(), query);
            return parsed;
        } catch (Exception e) {
            log.warn("Tavily search request failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }
}
