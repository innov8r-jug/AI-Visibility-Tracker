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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
     * Generate prompts based on category (uses category-specific prompts)
     */
    public List<String> generatePrompts(String categoryDisplayName) {
        return CategoryPrompts.getPrompts(categoryDisplayName);
    }
    
    /**
     * Query all AI models in parallel
     */
    @Async("aiQueryExecutor")
    public CompletableFuture<AIModelResponse> queryAIAsync(AIService service, String prompt, String category) {
        long startTime = System.currentTimeMillis();
        try {
            String response = service.query(prompt, category);
            long responseTime = System.currentTimeMillis() - startTime;
            List<Citation> citations = service.extractCitations(response);
            return CompletableFuture.completedFuture(
                    AIModelResponse.success(service.getModelType(), response, citations, responseTime)
            );
        } catch (Exception e) {
            log.error("Error querying {}: {}", service.getModelName(), e.getMessage());
            return CompletableFuture.completedFuture(
                    AIModelResponse.failed(service.getModelType(), e)
            );
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
        log.info("Starting visibility analysis - Category: {}, Brands: {}, Selected Models: {}",
                categoryDisplayName, brandNames, selectedModels);

        // Validate category
        if (!CategoryUtils.isValidCategory(categoryDisplayName)) {
            log.error("Invalid category: {}", categoryDisplayName);
            throw new IllegalArgumentException("Invalid category: " + categoryDisplayName);
        }

        // Convert display name to camelCase for storage
        String categoryCamelCase = CategoryUtils.toCamelCase(categoryDisplayName);
        log.debug("Converting category '{}' to camelCase: {}", categoryDisplayName, categoryCamelCase);

        Category category = categoryRepository.findByName(categoryCamelCase)
                .orElseGet(() -> {
                    log.info("Creating new category: {} (stored as: {})", categoryDisplayName, categoryCamelCase);
                    Category cat = new Category();
                    cat.setName(categoryCamelCase);
                    cat.setDescription(categoryDisplayName); // Store display name in description for reference
                    return categoryRepository.save(cat);
                });

        // Get or create brands
        List<Brand> brands = brandNames.stream()
                .map(name -> brandRepository.findByName(name)
                        .orElseGet(() -> {
                            log.debug("Creating new brand: {} for category: {}", name, category.getName());
                            Brand brand = new Brand();
                            brand.setName(name);
                            brand.setCategory(category);
                            return brandRepository.save(brand);
                        }))
                .collect(Collectors.toList());

        log.info("Processing {} brands for category: {}", brands.size(), category.getName());

        // Get AI services - FIXED: Logic was reversed!
        List<AIService> services;
        if (!CollectionUtils.isEmpty(selectedModels)) {
            services = aiServiceFactory.getServicesByModels(selectedModels);
            log.info("Using selected models: {}", selectedModels);
        } else {
            services = aiServiceFactory.getAllServices();
            log.info("No models selected, using all available services");
        }

        if (services.isEmpty()) {
            log.error("No AI services available! Check API keys configuration.");
            throw new IllegalStateException("No AI services available. Please configure API keys.");
        }

        log.info("Using {} AI service(s) for analysis: {}", services.size(),
                services.stream().map(AIService::getModelName).collect(Collectors.toList()));

        // Generate prompts using category-specific prompts
        List<String> prompts = generatePrompts(categoryDisplayName);
        log.info("Generated {} prompts for category: {}", prompts.size(), categoryDisplayName);

        // Query all AI models for each prompt
        int totalPromptsProcessed = 0;
        int totalSuccessfulResponses = 0;
        int totalFailedResponses = 0;

        for (String promptText : prompts) {
            totalPromptsProcessed++;
            log.debug("Processing prompt {}/{}: {}", totalPromptsProcessed, prompts.size(), promptText);
            List<CompletableFuture<AIModelResponse>> futures = services.stream()
                    .map(service -> queryAIAsync(service, promptText, category.getName()))
                    .collect(Collectors.toList());

            // Wait for all queries to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Process responses
            int successCount = 0;
            int failCount = 0;
            for (CompletableFuture<AIModelResponse> future : futures) {
                AIModelResponse response = future.join();
                if (response.isSuccess()) {
                    savePromptAndMentions(category, promptText, response, brands);
                    successCount++;
                    totalSuccessfulResponses++;
                } else {
                    String modelName = response.getModel() != null ? response.getModel().getDisplayName() : "Unknown";
                    String errorMsg = response.getErrorMessage() != null ? response.getErrorMessage() : "Unknown error";
                    log.warn("Failed to get response from {} for prompt: {}. Error: {}", modelName, promptText, errorMsg);
                    failCount++;
                    totalFailedResponses++;
                }
            }
            log.info("Prompt {}/{} completed - {} successful, {} failed responses",
                    totalPromptsProcessed, prompts.size(), successCount, failCount);
        }

        log.info("Analysis completed for category: {}. Summary - Prompts: {}, Successful: {}, Failed: {}",
                categoryDisplayName, totalPromptsProcessed, totalSuccessfulResponses, totalFailedResponses);
    }
    
    private void savePromptAndMentions(Category category, String promptText, AIModelResponse response, List<Brand> brands) {
        String modelName = response.getModel() != null ? response.getModel().getDisplayName() : "Unknown";
        log.info("Saving prompt and mentions for model: {}", modelName);

        if (response.getContent() == null || response.getContent().isEmpty()) {
            log.warn("Response content is empty for model: {}", modelName);
            return;
        }

        // Save prompt
        Prompt prompt = new Prompt();
        prompt.setCategory(category);
        prompt.setQueryText(promptText);
        prompt.setAiModel(response.getModel());
        prompt.setResponse(response.getContent());
        prompt = promptRepository.save(prompt);
        log.info("Saved prompt with ID: {} for model: {} (response length: {} chars)",
                prompt.getId(), modelName, response.getContent().length());

        // Extract brand mentions
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
                mention.setSentiment("NEUTRAL"); // TODO: Implement sentiment analysis
                mention = mentionRepository.save(mention);
                mentionCount++;
                mentionedBrands.add(brand.getName());

                log.info("Found mention of brand '{}' in response from {}", brand.getName(), modelName);

                // Save citations
                int citationCount = 0;
                if (response.getCitations() != null && !response.getCitations().isEmpty()) {
                    for (Citation citation : response.getCitations()) {
                        citation.setMention(mention);
                        citation.setAiModel(response.getModel());
                        citationRepository.save(citation);
                        citationCount++;
                    }
                    log.debug("Saved {} citations for brand '{}' mention", citationCount, brand.getName());
                } else {
                    log.debug("No citations found for brand '{}' mention from {}", brand.getName(), modelName);
                }
            }
        }
        if (mentionCount > 0) {
            log.info("Saved {} brand mention(s) for prompt from {}. Brands: {}", mentionCount, modelName, mentionedBrands);
        } else {
            log.info("No brand mentions found for prompt from {}", modelName);
        }
    }
    
    private String extractContext(String content, String brandName) {
        int index = content.indexOf(brandName);
        if (index == -1) return "";
        
        int start = Math.max(0, index - 100);
        int end = Math.min(content.length(), index + brandName.length() + 100);
        return content.substring(start, end);
    }
}

