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

    @Override
    public String query(String prompt, String category) throws Exception {
        String threadName = Thread.currentThread().getName();
        long startTime = System.currentTimeMillis();
        
        log.info("[GEMINI] [THREAD: {}] Starting query for category: {}", threadName, category);
        
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

            String responseText = textPart.get("text").getAsString();
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("[GEMINI] [THREAD: {}] ✓ Successfully received response ({} chars, took {} ms total)", 
                    threadName, responseText.length(), totalTime);
            
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
    
    @Override
    public List<Citation> extractCitations(String response) {
        List<Citation> citations = new ArrayList<>();
        
        if (response == null || response.isEmpty()) {
            return citations;
        }
        
        String urlPattern = "(?i)\\b(https?://[^\\s<>\"'{}|\\\\^`\\[\\]]+)|(www\\.[^\\s<>\"'{}|\\\\^`\\[\\]]+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(urlPattern);
        java.util.regex.Matcher matcher = pattern.matcher(response);
        
        java.util.Set<String> foundUrls = new java.util.HashSet<>();
        
        while (matcher.find()) {
            String url = matcher.group(0);
            url = url.replaceAll("[.,;:!?]+$", "");
            
            if (url.startsWith("www.")) {
                url = "https://" + url;
            }
            
            if (foundUrls.contains(url) || url.length() < 10) {
                continue;
            }
            
            try {
                new java.net.URL(url);
            } catch (java.net.MalformedURLException e) {
                continue;
            }
            
            foundUrls.add(url);
            
            String title = extractTitleFromMarkdown(response, url);
            if (title == null || title.isEmpty()) {
                title = extractDomainName(url);
            }
            
            Citation citation = new Citation();
            citation.setSourceUrl(url);
            citation.setSourceTitle(title);
            citations.add(citation);
        }
        
        log.info("Extracted {} unique citation(s) from Gemini response", citations.size());
        return citations;
    }
    
    private String extractTitleFromMarkdown(String response, String url) {
        String markdownPattern = "\\[([^\\]]+)\\]\\(" + java.util.regex.Pattern.quote(url) + "\\)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(markdownPattern, java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(response);
        
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    private String extractDomainName(String url) {
        try {
            java.net.URL urlObj = new java.net.URL(url);
            String host = urlObj.getHost();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception e) {
            return url.length() > 50 ? url.substring(0, 50) + "..." : url;
        }
    }
}

