package com.writesonic.visibility.service;

import com.writesonic.visibility.dto.CustomAnalysisResult;
import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.service.dto.AIModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicVisibilityService {

    private final AIServiceFactory aiServiceFactory;
    private final VisibilityPersistenceService persistenceService;

    public CustomAnalysisResult analyzeCustomPrompt(String userPrompt, List<String> targetBrands, List<AIModel> selectedModels) {
        long startTime = System.currentTimeMillis();

        // 1. STUB: Semantic Cache Check would go here (VectorDB integration)
        log.info("Checking Semantic Cache for intent match... (Missed)");

        // 2. Determine Models
        List<AIService> services = (selectedModels == null || selectedModels.isEmpty())
                ? aiServiceFactory.getAllServices()
                : aiServiceFactory.getServicesByModels(selectedModels);

        if (services.isEmpty()) {
            throw new IllegalStateException("No AI services available. Check API keys.");
        }

        // 3. Parallel Scatter-Gather
        List<CompletableFuture<AIModelResponse>> futures = services.stream()
                .map(service -> CompletableFuture.supplyAsync(() -> executeModelCall(service, userPrompt)))
                .collect(Collectors.toList());

        // 4. Wait for all to finish concurrently (Max 45 seconds timeout)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(45, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("One or more AI models timed out.");
        }

        // 5. Gather Successful Responses
        List<AIModelResponse> responses = futures.stream()
                .map(f -> f.getNow(null))
                .filter(Objects::nonNull)
                .filter(AIModelResponse::isSuccess)
                .collect(Collectors.toList());

        // 6. Extract Metrics
        Map<String, Object> metrics = extractMetrics(responses);

        // 7. Async Database Persistence (Fire and Forget)
        CompletableFuture.runAsync(() -> persistenceService.saveCustomAnalysis(userPrompt, responses));

        // 8. Immediate Return to Client
        return CustomAnalysisResult.builder()
                .prompt(userPrompt)
                .totalModelsQueried(services.size())
                .successfulResponses(responses.size())
                .executionTimeMs(System.currentTimeMillis() - startTime)
                .analysisData(metrics)
                .build();
    }

    private AIModelResponse executeModelCall(AIService service, String prompt) {
        long callStart = System.currentTimeMillis();
        try {
            String responseText = service.query(prompt, "Custom Search");
            var citations = service.extractCitations(responseText);
            return AIModelResponse.success(service.getModelType(), responseText, citations, System.currentTimeMillis() - callStart);
        } catch (Exception e) {
            return AIModelResponse.failed(service.getModelType(), e);
        }
    }

    private Map<String, Object> extractMetrics(List<AIModelResponse> responses) {
        Map<String, Integer> brandMentions = new HashMap<>();

        for (AIModelResponse response : responses) {
            // Regex to find bolded brand names: **BrandName**
            Matcher matcher = Pattern.compile("\\*\\*([^*]+)\\*\\*").matcher(response.getContent());
            while (matcher.find()) {
                String brand = matcher.group(1).trim();
                brandMentions.put(brand, brandMentions.getOrDefault(brand, 0) + 1);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("brandMentions", brandMentions);
        result.put("shareOfModel", calculateShareOfModel(brandMentions, responses.size()));
        return result;
    }

    private Map<String, Double> calculateShareOfModel(Map<String, Integer> mentions, int totalModels) {
        Map<String, Double> som = new HashMap<>();
        if (totalModels == 0) return som;

        mentions.forEach((brand, count) -> {
            som.put(brand, ((double) count / totalModels) * 100.0);
        });
        return som;
    }
}