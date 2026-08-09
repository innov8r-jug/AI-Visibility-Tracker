package com.writesonic.visibility.service.impl;

import com.google.gson.Gson;
import com.writesonic.visibility.config.AIConfig;
import com.writesonic.visibility.model.Citation;
import com.writesonic.visibility.service.AIService;
import com.writesonic.visibility.service.dto.AIQueryResult;
import com.writesonic.visibility.service.util.CitationTextExtractor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * Template Method base for single-request/single-response AI providers. Handles the
 * parts that are identical across every provider - timing, logging, timeout/error
 * handling, availability checking, and citation extraction - leaving only the
 * provider-specific request shape and response parsing to subclasses.
 * <p>
 * GroqService deliberately does NOT extend this: its multi-model fallback retry loop
 * is a genuinely different control flow, not just a different request/response shape,
 * so forcing it through a single-request template would need awkward hooks. It reuses
 * the shared {@link com.writesonic.visibility.service.util.OpenAiCompatibleChatClient}
 * and {@link CitationTextExtractor} helpers directly instead.
 */
@Slf4j
public abstract class AbstractAIService implements AIService {

    protected final AIConfig aiConfig;
    protected final OkHttpClient httpClient;
    protected final Gson gson = new Gson();

    protected AbstractAIService(AIConfig aiConfig, OkHttpClient httpClient) {
        this.aiConfig = aiConfig;
        this.httpClient = httpClient;
    }

    /** Builds the provider-specific HTTP request for the given prompt. */
    protected abstract Request buildRequest(String prompt);

    /** Extracts the assistant's answer text from a successful raw JSON response body. */
    protected abstract String parseResponseText(String rawJson) throws IOException;

    /** The provider's configured API key, used by the default {@link #isAvailable()}. */
    protected abstract String getApiKey();

    /**
     * Extracts provider-native "grounded" citations (an actual research trail the
     * provider searched) from the raw response, if the provider supports it. Default:
     * none - overridden only by providers with real search/grounding support (Gemini).
     */
    protected List<Citation> parseGroundedCitations(String rawJson) {
        return List.of();
    }

    @Override
    public final AIQueryResult query(String prompt, String category) throws Exception {
        String threadName = Thread.currentThread().getName();
        long startTime = System.currentTimeMillis();
        String tag = getModelType().name();

        log.info("[{}] [THREAD: {}] Starting query for category: {}", tag, threadName, category);

        if (!isAvailable()) {
            throw new IllegalStateException(getModelName() + " API key not configured");
        }

        Request request = buildRequest(prompt);

        try (Response response = httpClient.newCall(request).execute()) {
            String responseString = response.body() != null ? response.body().string() : null;

            if (!response.isSuccessful()) {
                throw new IOException(tag + " API error - Code: " + response.code() + ", Body: " + responseString);
            }
            if (responseString == null || responseString.isEmpty()) {
                throw new IOException("Empty response body from " + tag);
            }

            String responseText = parseResponseText(responseString);
            List<Citation> groundedCitations = parseGroundedCitations(responseString);
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("[{}] [THREAD: {}] ✓ Successfully received response ({} chars, {} grounded citation(s), took {} ms total)",
                    tag, threadName, responseText.length(), groundedCitations.size(), totalTime);
            return AIQueryResult.builder().text(responseText).groundedCitations(groundedCitations).build();
        } catch (SocketTimeoutException e) {
            log.error("[{}] [THREAD: {}] ✗ TIMEOUT after {} ms: {}",
                    tag, threadName, System.currentTimeMillis() - startTime, e.getMessage());
            throw e;
        } catch (IOException e) {
            log.error("[{}] [THREAD: {}] ✗ IO Exception after {} ms: {}",
                    tag, threadName, System.currentTimeMillis() - startTime, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[{}] [THREAD: {}] ✗ Exception after {} ms: {}",
                    tag, threadName, System.currentTimeMillis() - startTime, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public boolean isAvailable() {
        String key = getApiKey();
        return key != null && !key.isEmpty();
    }

    @Override
    public List<Citation> extractCitations(String response) {
        List<Citation> citations = CitationTextExtractor.extract(response);
        log.info("Extracted {} unique citation(s) from {} response", citations.size(), getModelName());
        return citations;
    }
}
