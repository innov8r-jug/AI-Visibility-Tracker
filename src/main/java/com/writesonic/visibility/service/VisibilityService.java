package com.writesonic.visibility.service;

import com.writesonic.visibility.model.*;
import com.writesonic.visibility.repository.*;
import com.writesonic.visibility.service.dto.AIModelResponse;
import com.writesonic.visibility.util.CategoryPrompts;
import com.writesonic.visibility.util.CategoryUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VisibilityService {
    
    private final AIServiceFactory aiServiceFactory;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final PromptRepository promptRepository;
    private final MentionRepository mentionRepository;
    private final CitationRepository citationRepository;
    
    /**
     * Generate prompts based on category and brands
     * Enhances category-specific prompts by including brand names explicitly
     * This ensures both Gemini and Groq process all brands in their responses
     * 
     * @param categoryDisplayName Display name of the category (e.g., "CRM Software")
     * @param brandNames List of brand names to include in prompts
     * @return List of enhanced prompts with brand names included
     */
    public List<String> generatePrompts(String categoryDisplayName, List<String> brandNames) {

        List<String> basePrompts = CategoryPrompts.getPrompts(categoryDisplayName);

        if (brandNames == null || brandNames.isEmpty()) {
            return basePrompts;
        }

        String brandsList = String.join(", ", brandNames);

        List<String> enhancedPrompts = new ArrayList<>();

        for (String basePrompt : basePrompts) {

            String enhancedPrompt =
                    basePrompt +
                            "\n\nYou are analyzing AI search visibility." +
                            "\nPlease evaluate ONLY the following brands: " + brandsList + "." +

                            "\n\nFor EACH brand:" +
                            "\n- Mention whether it appears prominently in AI-generated answers" +
                            "\n- Include the official website URL (full https:// link)" +
                            "\n- Include 1–2 authoritative third-party pages (reviews, comparisons, directories)" +
                            "\n\nRules:" +
                            "\n- Always include FULL URLs (https://...)" +
                            "\n- Do NOT invent sources" +
                            "\n- Use markdown bullet points" +

                            "\n\nExample format:" +
                            "\n- **Brand Name**" +
                            "\n  - https://officialsite.com" +
                            "\n  - https://authoritative-site.com/review";

            enhancedPrompts.add(enhancedPrompt);

            log.debug("Enhanced prompt with citation enforcement:\n{}", enhancedPrompt);
        }

        log.info(
                "Generated {} citation-enforced prompts for category '{}' with {} brands",
                enhancedPrompts.size(),
                categoryDisplayName,
                brandNames.size()
        );

        return enhancedPrompts;
    }

    @Async("aiQueryExecutor")
    public CompletableFuture<AIModelResponse> queryAIServiceAsync(AIService service, String prompt, String category) {
        String threadName = Thread.currentThread().getName();
        long startTime = System.currentTimeMillis();
        String serviceName = service.getModelName();
        
        log.debug("[THREAD: {}] Starting async query for service: {}", threadName, serviceName);
        
        log.info("[THREAD: {}] Querying {} for category: {}", threadName, serviceName, category);
        log.debug("[THREAD: {}] Prompt for {} (first 100 chars): {}", threadName, serviceName, 
                prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
        
        try {
            log.debug("[THREAD: {}] Calling service.query() for {}", threadName, serviceName);
            String response = service.query(prompt, category);
            long responseTime = System.currentTimeMillis() - startTime;
            
            log.debug("[THREAD: {}] Received response from {} ({} chars), extracting citations...", 
                    threadName, serviceName, response.length());
            
            List<Citation> citations = service.extractCitations(response);
            
            log.info("[THREAD: {}] ✓ Successfully received response from {} ({} ms, {} chars, {} citations)", 
                    threadName, serviceName, responseTime, response.length(), citations.size());
            
            AIModelResponse result = AIModelResponse.success(service.getModelType(), response, citations, responseTime);
            log.debug("[THREAD: {}] Returning successful response from {}", threadName, serviceName);
            return CompletableFuture.completedFuture(result);
            
        } catch (java.net.SocketTimeoutException e) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("[THREAD: {}] ✗ Timeout while querying {} (took {} ms): {}", 
                    threadName, serviceName, elapsedTime, e.getMessage());
            AIModelResponse result = AIModelResponse.failed(service.getModelType(), 
                    new Exception("Request timeout after " + elapsedTime + "ms: " + e.getMessage()));
            return CompletableFuture.completedFuture(result);
        } catch (java.io.IOException e) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("[THREAD: {}] ✗ IO error while querying {} (took {} ms): {}", 
                    threadName, serviceName, elapsedTime, e.getMessage());
            AIModelResponse result = AIModelResponse.failed(service.getModelType(), e);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("[THREAD: {}] ✗ Unexpected error while querying {} (took {} ms): {}", 
                    threadName, serviceName, elapsedTime, e.getMessage(), e);
            AIModelResponse result = AIModelResponse.failed(service.getModelType(), e);
            return CompletableFuture.completedFuture(result);
        }
    }
    
    /**
     * Analyze visibility for brands in a category
     * @param categoryDisplayName Display name (e.g., "CRM Software")
     * @param brandNames List of brand names
     * @param selectedModels List of selected AI models
     */
    @Transactional
    public void analyzeVisibility(String categoryDisplayName, List<String> brandNames, List<AIModel> selectedModels) {
        String threadName = Thread.currentThread().getName();
        long analysisStartTime = System.currentTimeMillis();
        
        log.info("[ANALYSIS] [THREAD: {}] ========================================", threadName);
        log.info("[ANALYSIS] [THREAD: {}] Starting visibility analysis", threadName);
        log.info("[ANALYSIS] [THREAD: {}] Category: {}", threadName, categoryDisplayName);
        log.info("[ANALYSIS] [THREAD: {}] Brands: {}", threadName, brandNames);
        log.info("[ANALYSIS] [THREAD: {}] Selected Models: {}", threadName, selectedModels);
        log.info("[ANALYSIS] [THREAD: {}] ========================================", threadName);

        if (!CategoryUtils.isValidCategory(categoryDisplayName)) {
            log.error("[ANALYSIS] [THREAD: {}] ✗ Invalid category: {}", threadName, categoryDisplayName);
            throw new IllegalArgumentException("Invalid category: " + categoryDisplayName);
        }

        String categoryCamelCase = CategoryUtils.toCamelCase(categoryDisplayName);
        log.debug("[ANALYSIS] [THREAD: {}] Converting category '{}' to camelCase: {}", 
                threadName, categoryDisplayName, categoryCamelCase);
        Long categoryId = getOrCreateCategoryId(categoryCamelCase, categoryDisplayName);
        log.debug("[ANALYSIS] [THREAD: {}] Category ID: {}", threadName, categoryId);
        
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> {
                    log.error("[ANALYSIS] [THREAD: {}] ✗ Category not found for id: {}", threadName, categoryId);
                    return new IllegalStateException("Category not found for id: " + categoryId);
                });
        log.debug("[ANALYSIS] [THREAD: {}] ✓ Category loaded: {}", threadName, category.getName());

        List<Brand> brands = brandNames.stream().map(
                name -> brandRepository.findByNameAndCategoryId(name, categoryId)
                        .orElseGet(() -> {
                            Brand brand = new Brand();
                            brand.setName(name);
                            brand.setCategory(category);
                            return brandRepository.save(brand);
                        }))
                .collect(Collectors.toList());

        log.info("[ANALYSIS] [THREAD: {}] Processing {} brands for category: {}", 
                threadName, brands.size(), category.getName());

        List<AIService> services;
        if (!CollectionUtils.isEmpty(selectedModels)) {
            services = aiServiceFactory.getServicesByModels(selectedModels);
            log.info("[ANALYSIS] [THREAD: {}] Using selected models: {}", threadName, selectedModels);
        } else {
            services = aiServiceFactory.getAllServices();
            log.info("[ANALYSIS] [THREAD: {}] No models selected, using all available services", threadName);
        }

        if (services.isEmpty()) {
            log.error("[ANALYSIS] [THREAD: {}] ✗ No AI services available! Check API keys configuration.", threadName);
            throw new IllegalStateException("No AI services available. Please configure API keys.");
        }

        log.info("[ANALYSIS] [THREAD: {}] Using {} AI service(s) for analysis: {}", threadName, services.size(),
                services.stream().map(AIService::getModelName).collect(Collectors.toList()));

        List<String> brandNamesFromDb = brands.stream()
                .map(Brand::getName)
                .toList();
        List<String> prompts = generatePrompts(categoryDisplayName, brandNamesFromDb);
        log.info("Generated {} enhanced prompts for category: {} with brands: {}", 
                prompts.size(), categoryDisplayName, brandNamesFromDb);

        int totalPromptsProcessed = 0;
        int totalSuccessfulResponses = 0;
        int totalFailedResponses = 0;

        log.info("[MAIN] Starting parallel processing of {} prompts with {} services", 
                prompts.size(), services.size());

        for (String promptText : prompts) {
            totalPromptsProcessed++;
            log.info("[MAIN] ========== Processing prompt {}/{} ==========", totalPromptsProcessed, prompts.size());
            
            List<CompletableFuture<AIModelResponse>> futures = new ArrayList<>();
            for (AIService service : services) {
                CompletableFuture<AIModelResponse> future = queryAIServiceAsync(
                        service, promptText, category.getName());
                futures.add(future);
            }
            
            log.info("[MAIN] Waiting for {} parallel API calls to complete for prompt {}...", 
                    futures.size(), totalPromptsProcessed);
            
            int successCount = 0;
            int failCount = 0;
            
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(60, TimeUnit.SECONDS)
                        .join();
            } catch (Exception e) {
                log.error("[MAIN] ✗ Timeout or error waiting for futures to complete for prompt {}: {}", 
                        totalPromptsProcessed, e.getMessage());
                for (int i = 0; i < futures.size(); i++) {
                    if (!futures.get(i).isDone()) {
                        failCount++;
                        totalFailedResponses++;
                    }
                }
            }
            
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<AIModelResponse> future = futures.get(i);
                AIService service = services.get(i);
                String serviceName = service.getModelName();
                
                try {
                    AIModelResponse response = future.get(5, TimeUnit.SECONDS);
                    
                    if (response.isSuccess()) {
                        try {
                            savePromptAndMentionsInNewTransaction(category, promptText, response, brands);
                            successCount++;
                            totalSuccessfulResponses++;
                            log.info("[MAIN] ✓ Successfully processed response from {} for prompt {}", 
                                    serviceName, totalPromptsProcessed);
                        } catch (org.springframework.dao.DataIntegrityViolationException e) {
                            log.error("[MAIN] ✗ Database constraint violation saving {} for prompt {}: {}", 
                                    serviceName, totalPromptsProcessed, e.getMessage());
                            failCount++;
                            totalFailedResponses++;
                        } catch (Exception e) {
                            log.error("[MAIN] ✗ Error saving {} for prompt {}: {}", 
                                    serviceName, totalPromptsProcessed, e.getMessage(), e);
                            failCount++;
                            totalFailedResponses++;
                        }
                    } else {
                        String errorMsg = response.getErrorMessage() != null ? 
                                response.getErrorMessage() : "Unknown error";
                        log.warn("[MAIN] ✗ Failed to get response from {} for prompt {}. Error: {}", 
                                serviceName, totalPromptsProcessed, errorMsg);
                        failCount++;
                        totalFailedResponses++;
                    }
                } catch (TimeoutException e) {
                    log.error("[MAIN] ✗ Timeout getting result from {} for prompt {}", 
                            serviceName, totalPromptsProcessed);
                    failCount++;
                    totalFailedResponses++;
                } catch (Exception e) {
                    log.error("[MAIN] ✗ Exception while getting result from {} for prompt {}: {}", 
                            serviceName, totalPromptsProcessed, e.getMessage(), e);
                    failCount++;
                    totalFailedResponses++;
                }
            }
            
            log.info("[MAIN] Prompt {}/{} completed - {} successful, {} failed responses across all services",
                    totalPromptsProcessed, prompts.size(), successCount, failCount);
        }

        long totalAnalysisTime = System.currentTimeMillis() - analysisStartTime;
        log.info("[ANALYSIS] [THREAD: {}] ========================================", threadName);
        log.info("[ANALYSIS] [THREAD: {}] Analysis completed for category: {}", threadName, categoryDisplayName);
        log.info("[ANALYSIS] [THREAD: {}] Summary:", threadName);
        log.info("[ANALYSIS] [THREAD: {}]   - Prompts processed: {}", threadName, totalPromptsProcessed);
        log.info("[ANALYSIS] [THREAD: {}]   - Successful responses: {}", threadName, totalSuccessfulResponses);
        log.info("[ANALYSIS] [THREAD: {}]   - Failed responses: {}", threadName, totalFailedResponses);
        log.info("[ANALYSIS] [THREAD: {}]   - Total time: {} ms ({} seconds)", 
                threadName, totalAnalysisTime, totalAnalysisTime / 1000.0);
        log.info("[ANALYSIS] [THREAD: {}] ========================================", threadName);
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePromptAndMentionsInNewTransaction(Category category, String promptText, AIModelResponse response, List<Brand> brands) {
        savePromptAndMentions(category, promptText, response, brands);
    }
    
    private void savePromptAndMentions(Category category, String promptText, AIModelResponse response, List<Brand> brands) {
        String threadName = Thread.currentThread().getName();
        String modelName = response.getModel() != null ? response.getModel().getDisplayName() : "Unknown";
        long startTime = System.currentTimeMillis();
        
        log.info("[SAVE] [THREAD: {}] Saving prompt and extracting mentions for model: {}", threadName, modelName);

        if (response.getContent() == null || response.getContent().isEmpty()) {
            log.warn("[SAVE] [THREAD: {}] ✗ Response content is empty for model: {} - skipping save", 
                    threadName, modelName);
            return;
        }

        Prompt prompt = new Prompt();
        prompt.setCategory(category);
        prompt.setQueryText(promptText);
        prompt.setAiModel(response.getModel());
        prompt.setResponse(response.getContent());
        prompt = promptRepository.save(prompt);
        log.info("[SAVE] [THREAD: {}] ✓ Saved prompt with ID: {} for model: {} (response length: {} chars)",
                threadName, prompt.getId(), modelName, response.getContent().length());

        String content = response.getContent().toLowerCase();
        int mentionCount = 0;
        List<String> mentionedBrands = new ArrayList<>();

        for (Brand brand : brands) {
            String brandName = brand.getName().toLowerCase();
            
            if (content.contains(brandName)) {
                Mention mention = new Mention();
                mention.setPrompt(prompt);
                mention.setBrand(brand);
                mention.setAiModel(response.getModel());
                mention.setContext(extractContext(content, brandName));
                mention.setSentiment("NEUTRAL");
                
                mention = mentionRepository.save(mention);
                mentionCount++;
                mentionedBrands.add(brand.getName());

                log.info("[SAVE] [THREAD: {}] ✓ Found and saved mention of brand '{}' in response from {}", 
                        threadName, brand.getName(), modelName);

                if (response.getCitations() != null && !response.getCitations().isEmpty()) {
                    for (Citation citation : response.getCitations()) {
                        citation.setMention(mention);
                        citation.setAiModel(response.getModel());
                        citationRepository.save(citation);
                    }
                }
            }
        }
        
        long saveTime = System.currentTimeMillis() - startTime;
        if (mentionCount > 0) {
            log.info("[SAVE] [THREAD: {}] ✓ Saved {} brand mention(s) for prompt from {} (took {} ms). Brands: {}", 
                    threadName, mentionCount, modelName, saveTime, mentionedBrands);
        } else {
            log.warn("[SAVE] [THREAD: {}] ⚠ No brand mentions found in response from {} for prompt: {} (took {} ms)", 
                    threadName, modelName, promptText.substring(0, Math.min(50, promptText.length())), saveTime);
        }
    }
    
    private String extractContext(String content, String brandName) {
        int index = content.indexOf(brandName);
        if (index == -1) {
            return "";
        }
        int start = Math.max(0, index - 100);
        int end = Math.min(content.length(), index + brandName.length() + 100);
        return content.substring(start, end);
    }

    public Long getOrCreateCategoryId(String categoryCamelCase, String categoryDisplayName) {
        Category category = categoryRepository.findByName(categoryCamelCase)
                .orElseGet(() -> {
                    log.info("Creating new category: {} (stored as: {})", categoryDisplayName, categoryCamelCase);
                    Category newCategory = new Category();
                    newCategory.setName(categoryCamelCase);
                    newCategory.setDescription(categoryDisplayName);
                    return categoryRepository.save(newCategory);
                });
        return category.getId();
    }

}

