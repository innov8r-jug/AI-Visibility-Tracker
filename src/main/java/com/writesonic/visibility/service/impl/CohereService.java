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
import com.writesonic.visibility.service.dto.TavilySearchResult;
import com.writesonic.visibility.service.tools.TavilySearchService;
import com.writesonic.visibility.service.util.CitationTextExtractor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Does NOT extend AbstractAIService: unlike single-request providers, Cohere here needs
 * a multi-turn tool-calling loop (ask Cohere -> if it requests a web search, run that
 * search via Tavily -> feed results back -> get the final grounded answer) - a genuinely
 * different control flow, matching why GroqService also implements AIService directly.
 * <p>
 * Verified against live command-a-plus-05-2026 calls: it can request multiple distinct
 * follow-up searches for broad/open-ended queries (see MAX_TOOL_ROUNDS below), and it
 * rejects the tool_choice parameter outright, which is why the loop lets it call the
 * tool freely instead of trying to force a final answer on a fixed turn.
 */
@Service
@Slf4j
public class CohereService implements AIService {

    private static final String WEB_SEARCH_TOOL_NAME = "web_search";
    // command-a-plus-05-2026 can genuinely want multiple distinct follow-up searches for
    // broad/open-ended queries (observed doing 2 real, different Tavily searches before
    // hitting the old cap of 2 and still asking for a 3rd). Bumped further to 10 -
    // WARNING: each round is a full Cohere HTTP call + a Tavily search (~4s/round
    // observed), so 10 rounds could take ~40s+, right up against DynamicVisibilityService's
    // 45s per-model orTimeout. If Cohere starts timing out instead of hitting this cap,
    // that's this constant being too high for the available time budget, not a new bug.
    private static final int MAX_TOOL_ROUNDS = 10;

    private final AIConfig aiConfig;
    private final OkHttpClient httpClient;
    private final TavilySearchService tavilySearchService;
    private final Gson gson = new Gson();

    public CohereService(AIConfig aiConfig, OkHttpClient httpClient, TavilySearchService tavilySearchService) {
        this.aiConfig = aiConfig;
        this.httpClient = httpClient;
        this.tavilySearchService = tavilySearchService;
    }

    @Override
    public AIQueryResult query(String prompt, String category) throws Exception {
        String threadName = Thread.currentThread().getName();
        long startTime = System.currentTimeMillis();

        log.info("[COHERE] [THREAD: {}] Starting query for category: {}", threadName, category);

        if (!isAvailable()) {
            throw new IllegalStateException("Cohere API key not configured");
        }

        try {
            JsonArray messages = new JsonArray();
            messages.add(userMessage(prompt));

            List<Citation> groundedCitations = new ArrayList<>();

            // Bounded tool-calling loop instead of a tool_choice="none" forced final
            // answer: command-a-plus-05-2026 rejects tool_choice entirely ("tool_choice
            // is not supported for this model"), so there's no way to force a text-only
            // reply on demand. Instead we just let Cohere call the tool as many times as
            // it wants, up to MAX_TOOL_ROUNDS, and take whatever final text answer it
            // gives once it stops requesting tools.
            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                JsonObject turn = executeChatCall(messages);
                JsonObject message = turn.getAsJsonObject("message");
                JsonArray toolCalls = message != null ? message.getAsJsonArray("tool_calls") : null;

                if (toolCalls == null || toolCalls.isEmpty()) {
                    String text = extractText(message);
                    if (text == null || text.isBlank()) {
                        throw new IOException("Cohere returned no text content and no further tool calls");
                    }
                    logSuccess(threadName, startTime, text, groundedCitations.size());
                    return AIQueryResult.builder().text(text).groundedCitations(groundedCitations).build();
                }

                if (round == MAX_TOOL_ROUNDS) {
                    throw new IOException("Cohere kept requesting tool calls after " + MAX_TOOL_ROUNDS + " round(s) without a final answer");
                }

                messages.add(message);
                for (JsonElement toolCallEl : toolCalls) {
                    JsonObject toolCall = toolCallEl.getAsJsonObject();
                    String toolCallId = toolCall.has("id") ? toolCall.get("id").getAsString() : null;
                    JsonObject function = toolCall.getAsJsonObject("function");
                    String searchQuery = extractSearchQuery(function, prompt);

                    List<TavilySearchResult> results = tavilySearchService.search(searchQuery);
                    for (TavilySearchResult result : results) {
                        if (result.getUrl() == null) continue;
                        Citation citation = new Citation();
                        citation.setSourceUrl(result.getUrl());
                        citation.setSourceTitle(result.getTitle());
                        groundedCitations.add(citation);
                    }

                    JsonObject toolResultMessage = new JsonObject();
                    toolResultMessage.addProperty("role", "tool");
                    if (toolCallId != null) {
                        toolResultMessage.addProperty("tool_call_id", toolCallId);
                    }
                    toolResultMessage.addProperty("content", gson.toJson(results));
                    messages.add(toolResultMessage);
                }
            }

            // Unreachable: the loop above always returns or throws.
            throw new IOException("Cohere tool-calling loop ended unexpectedly");
        } catch (SocketTimeoutException e) {
            log.error("[COHERE] [THREAD: {}] ✗ TIMEOUT after {} ms: {}",
                    threadName, System.currentTimeMillis() - startTime, e.getMessage());
            throw e;
        } catch (IOException e) {
            log.error("[COHERE] [THREAD: {}] ✗ IO Exception after {} ms: {}",
                    threadName, System.currentTimeMillis() - startTime, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[COHERE] [THREAD: {}] ✗ Exception after {} ms: {}",
                    threadName, System.currentTimeMillis() - startTime, e.getMessage(), e);
            throw e;
        }
    }

    private void logSuccess(String threadName, long startTime, String text, int groundedCitationCount) {
        long totalTime = System.currentTimeMillis() - startTime;
        log.info("[COHERE] [THREAD: {}] ✓ Successfully received response ({} chars, {} grounded citation(s), took {} ms total)",
                threadName, text.length(), groundedCitationCount, totalTime);
    }

    private JsonObject executeChatCall(JsonArray messages) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", aiConfig.getCohereApiModel());
        requestBody.add("messages", messages);
        // Tool definitions must be present on every call in this conversation, not just
        // the first - Cohere needs the schema to validate/interpret tool_calls it made in
        // earlier turns, even on a follow-up request that isn't asking it to call again.
        // NOTE: deliberately no tool_choice param - command-a-plus-05-2026 rejects it
        // outright ("tool_choice is not supported for this model"), so there's no way to
        // force a text-only reply; the bounded loop in query() handles that instead.
        requestBody.add("tools", buildWebSearchToolDefinition());

        Request request = new Request.Builder()
                .url(aiConfig.getCohereApiUrl())
                .post(RequestBody.create(gson.toJson(requestBody), MediaType.get("application/json")))
                .addHeader("Authorization", "Bearer " + aiConfig.getCohereApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseString = response.body() != null ? response.body().string() : null;

            if (!response.isSuccessful()) {
                throw new IOException("Cohere API error - Code: " + response.code() + ", Body: " + responseString);
            }
            if (responseString == null || responseString.isEmpty()) {
                throw new IOException("Empty response body from Cohere");
            }
            return gson.fromJson(responseString, JsonObject.class);
        }
    }

    private JsonArray buildWebSearchToolDefinition() {
        JsonObject queryProperty = new JsonObject();
        queryProperty.addProperty("type", "string");
        queryProperty.addProperty("description", "The search query");

        JsonObject properties = new JsonObject();
        properties.add("query", queryProperty);

        JsonArray required = new JsonArray();
        required.add("query");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);

        JsonObject function = new JsonObject();
        function.addProperty("name", WEB_SEARCH_TOOL_NAME);
        function.addProperty("description", "Search the web for current, real-time information relevant to the user's question.");
        function.add("parameters", parameters);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);

        JsonArray tools = new JsonArray();
        tools.add(tool);
        return tools;
    }

    private JsonObject userMessage(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", content);
        return message;
    }

    private String extractText(JsonObject messageObj) throws IOException {
        if (messageObj == null) {
            throw new IOException("No 'message' object in Cohere response");
        }
        JsonArray contentBlocks = messageObj.getAsJsonArray("content");
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement el : contentBlocks) {
            JsonObject block = el.getAsJsonObject();
            if (block.has("text")) {
                text.append(block.get("text").getAsString());
            }
        }
        return text.toString();
    }

    private String extractSearchQuery(JsonObject function, String fallbackPrompt) {
        try {
            if (function != null && function.has("arguments")) {
                String argsRaw = function.get("arguments").getAsString();
                JsonObject args = gson.fromJson(argsRaw, JsonObject.class);
                if (args != null && args.has("query")) {
                    return args.get("query").getAsString();
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Cohere tool-call arguments, using original prompt as search query", e);
        }
        return fallbackPrompt;
    }

    @Override
    public String getModelName() {
        return "Cohere AI";
    }

    @Override
    public AIModel getModelType() {
        return AIModel.COHERE;
    }

    @Override
    public boolean isAvailable() {
        return aiConfig.getCohereApiKey() != null && !aiConfig.getCohereApiKey().isEmpty();
    }

    @Override
    public List<Citation> extractCitations(String response) {
        List<Citation> citations = CitationTextExtractor.extract(response);
        log.info("Extracted {} unique citation(s) from Cohere AI response", citations.size());
        return citations;
    }
}
