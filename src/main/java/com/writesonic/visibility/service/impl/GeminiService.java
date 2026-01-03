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
public class GeminiService implements AIService {
    
    private final AIConfig aiConfig;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    /**
     * Query Google Gemini API with a prompt
     * This method sends a synchronous request to Gemini and returns the response text
     * 
     * Flow:
     * 1. Check if API key is configured
     * 2. Build JSON request body in Gemini's format
     * 3. Send HTTP POST request to Gemini API
     * 4. Parse response and extract text content
     * 5. Return the response text (which may contain multiple brand mentions)
     * 
     * @param prompt The question/prompt to send to Gemini (includes brand names)
     * @param category Category name (for logging)
     * @return The AI's response text as a string
     * @throws Exception If API call fails or response is invalid
     */
    @Override
    public String query(String prompt, String category) throws Exception {
        String threadName = Thread.currentThread().getName();
        long startTime = System.currentTimeMillis();
        
        log.info("[GEMINI] [THREAD: {}] Starting query for category: {}", threadName, category);
        log.debug("[GEMINI] [THREAD: {}] Prompt length: {} chars", threadName, prompt.length());
        log.debug("[GEMINI] [THREAD: {}] Prompt (first 100 chars): {}", threadName, 
                prompt.substring(0, Math.min(100, prompt.length())));
        
        // Step 1: Validate API key is configured
        log.debug("[GEMINI] [THREAD: {}] Checking if API key is available...", threadName);
        if (!isAvailable()) {
            log.error("[GEMINI] [THREAD: {}] ✗ API key not configured", threadName);
            throw new IllegalStateException("Google API key not configured");
        }
        log.debug("[GEMINI] [THREAD: {}] ✓ API key is available", threadName);

        // Step 2: Build request body in Gemini's JSON format
        // Gemini expects: { "contents": [{ "parts": [{ "text": "prompt" }] }] }
        log.debug("[GEMINI] [THREAD: {}] Building request body...", threadName);
        JsonObject requestBody = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();

        part.addProperty("text", prompt);  // The prompt text (includes brand names)
        parts.add(part);
        content.add("parts", parts);
        contents.add(content);
        requestBody.add("contents", contents);
        log.debug("[GEMINI] [THREAD: {}] Request body built, JSON size: {} chars", 
                threadName, gson.toJson(requestBody).length());

        // Step 3: Build API URL with API key as query parameter
        String url = aiConfig.getGoogleApiUrl() + "?key=" + aiConfig.getGoogleApiKey();
        log.debug("[GEMINI] [THREAD: {}] API URL: {} (key hidden)", threadName, aiConfig.getGoogleApiUrl());

        // Step 4: Create HTTP POST request
        log.debug("[GEMINI] [THREAD: {}] Creating HTTP request...", threadName);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        gson.toJson(requestBody),
                        MediaType.get("application/json")
                ))
                .build();
        log.debug("[GEMINI] [THREAD: {}] HTTP request created, executing...", threadName);

        // Step 5: Execute HTTP request and parse response
        long httpStartTime = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            long httpElapsed = System.currentTimeMillis() - httpStartTime;
            log.debug("[GEMINI] [THREAD: {}] HTTP response received (took {} ms), status: {}", 
                    threadName, httpElapsed, response.code());
            log.debug("[GEMINI] [THREAD: {}] Reading response body...", threadName);
            ResponseBody responseBody = response.body();
            String responseString = responseBody != null ? responseBody.string() : null;
            log.debug("[GEMINI] [THREAD: {}] Response body read, length: {} chars", 
                    threadName, responseString != null ? responseString.length() : 0);

            // Check HTTP status code
            if (!response.isSuccessful()) {
                log.error("[GEMINI] [THREAD: {}] ✗ HTTP error - Code: {}, Body: {}", 
                        threadName, response.code(), 
                        responseString != null && responseString.length() > 500 ? 
                                responseString.substring(0, 500) : responseString);
                throw new IOException("Gemini API error - Code: " + response.code() + ", Body: " + responseString);
            }
            log.debug("[GEMINI] [THREAD: {}] ✓ HTTP status OK: {}", threadName, response.code());

            // Validate response body exists
            if (responseString == null || responseString.isEmpty()) {
                log.error("[GEMINI] [THREAD: {}] ✗ Empty response body", threadName);
                throw new IOException("Empty response body from Gemini");
            }

            // Parse JSON response
            log.debug("[GEMINI] [THREAD: {}] Parsing JSON response...", threadName);
            JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);
            log.debug("[GEMINI] [THREAD: {}] JSON parsed successfully", threadName);

            // Check for API errors in response
            if (jsonResponse.has("error")) {
                JsonObject error = jsonResponse.getAsJsonObject("error");
                String errorMessage = error.has("message") ? error.get("message").getAsString() : "Unknown error";
                log.error("[GEMINI] [THREAD: {}] ✗ API error in response: {}", threadName, errorMessage);
                throw new IOException("Gemini API error: " + errorMessage);
            }
            log.debug("[GEMINI] [THREAD: {}] No errors in JSON response", threadName);

            // Extract text from Gemini's response structure:
            // response.candidates[0].content.parts[0].text
            log.debug("[GEMINI] [THREAD: {}] Extracting candidates array...", threadName);
            JsonArray candidates = jsonResponse.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                log.error("[GEMINI] [THREAD: {}] ✗ No candidates in response", threadName);
                throw new IOException("No candidates in Gemini response");
            }
            log.debug("[GEMINI] [THREAD: {}] Found {} candidate(s)", threadName, candidates.size());

            log.debug("[GEMINI] [THREAD: {}] Extracting candidate[0]...", threadName);
            JsonObject candidate = candidates.get(0).getAsJsonObject();
            JsonObject contentObj = candidate.getAsJsonObject("content");
            if (contentObj == null) {
                log.error("[GEMINI] [THREAD: {}] ✗ No content in candidate", threadName);
                throw new IOException("No content in Gemini candidate");
            }
            log.debug("[GEMINI] [THREAD: {}] Content object found", threadName);

            log.debug("[GEMINI] [THREAD: {}] Extracting parts array...", threadName);
            JsonArray responseParts = contentObj.getAsJsonArray("parts");
            if (responseParts == null || responseParts.isEmpty()) {
                log.error("[GEMINI] [THREAD: {}] ✗ No parts in content", threadName);
                throw new IOException("No parts in Gemini content");
            }
            log.debug("[GEMINI] [THREAD: {}] Found {} part(s)", threadName, responseParts.size());

            log.debug("[GEMINI] [THREAD: {}] Extracting text from part[0]...", threadName);
            JsonObject textPart = responseParts.get(0).getAsJsonObject();
            if (!textPart.has("text")) {
                log.error("[GEMINI] [THREAD: {}] ✗ No text field in part", threadName);
                throw new IOException("No text in Gemini response part");
            }

            // Extract and return the response text
            String responseText = textPart.get("text").getAsString();
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("[GEMINI] [THREAD: {}] ✓ Successfully received response ({} chars, took {} ms total)", 
                    threadName, responseText.length(), totalTime);
            log.debug("[GEMINI] [THREAD: {}] Response (first 200 chars): {}", 
                    threadName, responseText.substring(0, Math.min(200, responseText.length())));
            
            return responseText;
        } catch (java.net.SocketTimeoutException e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("[GEMINI] [THREAD: {}] ✗ TIMEOUT after {} ms: {}", threadName, totalTime, e.getMessage());
            throw e;
        } catch (java.io.IOException e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("[GEMINI] [THREAD: {}] ✗ IO Exception after {} ms: {}", threadName, totalTime, e.getMessage());
            throw e;
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("[GEMINI] [THREAD: {}] ✗ Exception after {} ms: {}", threadName, totalTime, e.getMessage(), e);
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
    
    /**
     * Extract citations (URLs) from Gemini response text
     * Searches for URLs in the response and creates Citation objects
     * 
     * Citation patterns to look for:
     * - Direct URLs: https://example.com, http://example.com
     * - URLs in markdown: [text](https://example.com)
     * - URLs in parentheses: (https://example.com)
     * - URLs in quotes: "https://example.com"
     * 
     * @param response The AI response text to extract citations from
     * @return List of Citation objects with URLs found in the response
     */
    @Override
    public List<Citation> extractCitations(String response) {
        List<Citation> citations = new ArrayList<>();
        
        if (response == null || response.isEmpty()) {
            log.debug("Empty response, no citations to extract");
            return citations;
        }
        
        log.debug("Extracting citations from Gemini response (length: {} chars)", response.length());
        
        // Regex pattern to match URLs
        // Matches: http://, https://, and optionally www.
        // Also handles URLs in markdown links: [text](url)
        String urlPattern = "(?i)\\b(https?://[^\\s<>\"'{}|\\\\^`\\[\\]]+)|(www\\.[^\\s<>\"'{}|\\\\^`\\[\\]]+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(urlPattern);
        java.util.regex.Matcher matcher = pattern.matcher(response);
        
        // Track unique URLs to avoid duplicates
        java.util.Set<String> foundUrls = new java.util.HashSet<>();
        
        while (matcher.find()) {
            String url = matcher.group(0);
            
            // Clean up URL - remove trailing punctuation that might not be part of URL
            url = url.replaceAll("[.,;:!?]+$", "");
            
            // Ensure URL has protocol
            if (url.startsWith("www.")) {
                url = "https://" + url;
            }
            
            // Skip if already found or invalid
            if (foundUrls.contains(url) || url.length() < 10) {
                continue;
            }
            
            // Validate URL format
            try {
                new java.net.URL(url);
            } catch (java.net.MalformedURLException e) {
                log.debug("Skipping invalid URL: {}", url);
                continue;
            }
            
            foundUrls.add(url);
            
            // Extract title if available (look for markdown format: [title](url))
            String title = extractTitleFromMarkdown(response, url);
            if (title == null || title.isEmpty()) {
                // Use domain name as title if no title found
                title = extractDomainName(url);
            }
            
            // Create Citation object (mention and aiModel will be set later in VisibilityService)
            Citation citation = new Citation();
            citation.setSourceUrl(url);
            citation.setSourceTitle(title);
            citations.add(citation);
            
            log.debug("Extracted citation: {} - {}", title, url);
        }
        
        log.info("Extracted {} unique citation(s) from Gemini response", citations.size());
        return citations;
    }
    
    /**
     * Extract title from markdown link format: [title](url)
     * @param response The full response text
     * @param url The URL to find title for
     * @return Title if found, null otherwise
     */
    private String extractTitleFromMarkdown(String response, String url) {
        // Look for markdown link pattern: [title](url)
        String markdownPattern = "\\[([^\\]]+)\\]\\(" + java.util.regex.Pattern.quote(url) + "\\)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(markdownPattern, java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(response);
        
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    /**
     * Extract domain name from URL to use as title
     * @param url The URL
     * @return Domain name (e.g., "example.com" from "https://www.example.com/path")
     */
    private String extractDomainName(String url) {
        try {
            java.net.URL urlObj = new java.net.URL(url);
            String host = urlObj.getHost();
            // Remove www. prefix if present
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception e) {
            // If URL parsing fails, return a simplified version
            return url.length() > 50 ? url.substring(0, 50) + "..." : url;
        }
    }
}

