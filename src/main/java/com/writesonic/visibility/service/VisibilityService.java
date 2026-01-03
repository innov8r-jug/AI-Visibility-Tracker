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


    /**
     * Query a single AI service synchronously (one at a time, no threading)
     * This method processes AI queries sequentially for easier debugging
     * 
     * @param service The AI service to query (Gemini, Groq, etc.)
     * @param prompt The prompt/question to send to the AI
     * @param category The category name (for logging purposes)
     * @return AIModelResponse containing the AI's response or error information
     */
    /**
     * Query a single AI service asynchronously (parallel processing)
     * This method processes AI queries in parallel using thread pool for better performance
     * 
     * Note: HTTP client has 30s timeout, so this won't hang forever.
     * If API call takes longer than 30s, it will timeout and return a failed response.
     * 
     * @param service The AI service to query (Gemini, Groq, etc.)
     * @param prompt The prompt/question to send to the AI
     * @param category The category name (for logging purposes)
     * @return CompletableFuture<AIModelResponse> containing the AI's response or error information
     */
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
            // Call the AI service's query method (this runs in async thread)
            // HTTP client timeout is 30s, so this will fail fast if API is slow
            String response = service.query(prompt, category);
            long responseTime = System.currentTimeMillis() - startTime;
            
            log.debug("[THREAD: {}] Received response from {} ({} chars), extracting citations...", 
                    threadName, serviceName, response.length());
            
            // Extract citations from the response (URLs, sources, etc.)
            List<Citation> citations = service.extractCitations(response);
            
            log.info("[THREAD: {}] ✓ Successfully received response from {} ({} ms, {} chars, {} citations)", 
                    threadName, serviceName, responseTime, response.length(), citations.size());
            
            // Return successful response wrapped in CompletableFuture
            AIModelResponse result = AIModelResponse.success(service.getModelType(), response, citations, responseTime);
            log.debug("[THREAD: {}] Returning successful response from {}", threadName, serviceName);
            return CompletableFuture.completedFuture(result);
            
        } catch (java.net.SocketTimeoutException e) {
            // Handle timeout specifically
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("[THREAD: {}] ✗ Timeout while querying {} (took {} ms): {}", 
                    threadName, serviceName, elapsedTime, e.getMessage());
            AIModelResponse result = AIModelResponse.failed(service.getModelType(), 
                    new Exception("Request timeout after " + elapsedTime + "ms: " + e.getMessage()));
            return CompletableFuture.completedFuture(result);
        } catch (java.io.IOException e) {
            // Handle IO errors (network issues, API errors)
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("[THREAD: {}] ✗ IO error while querying {} (took {} ms): {}", 
                    threadName, serviceName, elapsedTime, e.getMessage());
            AIModelResponse result = AIModelResponse.failed(service.getModelType(), e);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            // Handle any other unexpected errors
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

        // Validate category
        log.debug("[ANALYSIS] [THREAD: {}] Validating category...", threadName);
        if (!CategoryUtils.isValidCategory(categoryDisplayName)) {
            log.error("[ANALYSIS] [THREAD: {}] ✗ Invalid category: {}", threadName, categoryDisplayName);
            throw new IllegalArgumentException("Invalid category: " + categoryDisplayName);
        }
        log.debug("[ANALYSIS] [THREAD: {}] ✓ Category is valid", threadName);

        // Convert display name to camelCase for storage
        String categoryCamelCase = CategoryUtils.toCamelCase(categoryDisplayName);
        log.debug("[ANALYSIS] [THREAD: {}] Converting category '{}' to camelCase: {}", 
                threadName, categoryDisplayName, categoryCamelCase);

        log.debug("[ANALYSIS] [THREAD: {}] Getting or creating category...", threadName);
        Long categoryId = getOrCreateCategoryId(categoryCamelCase, categoryDisplayName);
        log.debug("[ANALYSIS] [THREAD: {}] Category ID: {}", threadName, categoryId);
        
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> {
                    log.error("[ANALYSIS] [THREAD: {}] ✗ Category not found for id: {}", threadName, categoryId);
                    return new IllegalStateException("Category not found for id: " + categoryId);
                });
        log.debug("[ANALYSIS] [THREAD: {}] ✓ Category loaded: {}", threadName, category.getName());

        // Get or create brands
        log.debug("[ANALYSIS] [THREAD: {}] Getting or creating {} brands...", threadName, brandNames.size());
        List<Brand> brands = brandNames.stream().map(
                name -> brandRepository.findByNameAndCategoryId(name, categoryId)
                        .orElseGet(() -> {
                            log.debug("[ANALYSIS] [THREAD: {}] Creating new brand: {} for category_id: {}", 
                                    threadName, name, categoryId);
                            Brand brand = new Brand();
                            brand.setName(name);
                            brand.setCategory(category);
                            Brand saved = brandRepository.save(brand);
                            log.debug("[ANALYSIS] [THREAD: {}] ✓ Created brand: {} (ID: {})", 
                                    threadName, name, saved.getId());
                            return saved;
                        }))
                .collect(Collectors.toList());

        log.info("[ANALYSIS] [THREAD: {}] Processing {} brands for category: {}", 
                threadName, brands.size(), category.getName());
        log.debug("[ANALYSIS] [THREAD: {}] Brand names: {}", 
                threadName, brands.stream().map(Brand::getName).collect(Collectors.toList()));

        // Get AI services
        log.debug("[ANALYSIS] [THREAD: {}] Getting AI services...", threadName);
        List<AIService> services;
        if (!CollectionUtils.isEmpty(selectedModels)) {
            log.debug("[ANALYSIS] [THREAD: {}] Using selected models: {}", threadName, selectedModels);
            services = aiServiceFactory.getServicesByModels(selectedModels);
            log.info("[ANALYSIS] [THREAD: {}] Using selected models: {}", threadName, selectedModels);
        } else {
            log.debug("[ANALYSIS] [THREAD: {}] No models selected, getting all available services", threadName);
            services = aiServiceFactory.getAllServices();
            log.info("[ANALYSIS] [THREAD: {}] No models selected, using all available services", threadName);
        }

        if (services.isEmpty()) {
            log.error("[ANALYSIS] [THREAD: {}] ✗ No AI services available! Check API keys configuration.", threadName);
            throw new IllegalStateException("No AI services available. Please configure API keys.");
        }

        log.info("[ANALYSIS] [THREAD: {}] Using {} AI service(s) for analysis: {}", threadName, services.size(),
                services.stream().map(AIService::getModelName).collect(Collectors.toList()));
        log.debug("[ANALYSIS] [THREAD: {}] Service availability check:", threadName);
        for (AIService service : services) {
            log.debug("[ANALYSIS] [THREAD: {}]   - {}: available={}", 
                    threadName, service.getModelName(), service.isAvailable());
        }

        // Generate enhanced prompts that include brand names explicitly
        // This ensures all AI models (especially Groq) process all brands
        // Extract brand names from Brand objects (use database names for consistency)
        List<String> brandNamesFromDb = brands.stream()
                .map(Brand::getName)
                .toList();
        List<String> prompts = generatePrompts(categoryDisplayName, brandNamesFromDb);
        log.info("Generated {} enhanced prompts for category: {} with brands: {}", 
                prompts.size(), categoryDisplayName, brandNamesFromDb);

        // Process prompts and AI services in parallel (multi-threaded)
        // Outer loop: Process each prompt one at a time (prompts are processed sequentially)
        // Inner processing: All AI services for a prompt are queried in parallel
        int totalPromptsProcessed = 0;
        int totalSuccessfulResponses = 0;
        int totalFailedResponses = 0;

        log.info("[MAIN] Starting parallel processing of {} prompts with {} services", 
                prompts.size(), services.size());
        log.debug("[MAIN] Services: {}", services.stream().map(AIService::getModelName).collect(Collectors.toList()));

        for (String promptText : prompts) {
            totalPromptsProcessed++;
            log.info("[MAIN] ========== Processing prompt {}/{} ==========", totalPromptsProcessed, prompts.size());
            log.debug("[MAIN] Prompt text: {}", promptText);
            
            // Create futures for all services - they will execute in parallel
            List<CompletableFuture<AIModelResponse>> futures = new ArrayList<>();
            log.debug("[MAIN] Creating {} async tasks for prompt {}", services.size(), totalPromptsProcessed);
            
            for (AIService service : services) {
                String serviceName = service.getModelName();
                log.debug("[MAIN] Submitting async task for {} (prompt {})", serviceName, totalPromptsProcessed);
                
                // Submit async task - this returns immediately, execution happens in thread pool
                CompletableFuture<AIModelResponse> future = queryAIServiceAsync(
                        service, promptText, category.getName());
                futures.add(future);
                log.debug("[MAIN] Async task submitted for {}, future: {}", serviceName, future);
            }
            
            log.info("[MAIN] Waiting for {} parallel API calls to complete for prompt {}...", 
                    futures.size(), totalPromptsProcessed);
            
            // Process responses from all services
            int successCount = 0;
            int failCount = 0;
            
            // Wait for all futures to complete (all services queried in parallel)
            // Add timeout to prevent hanging forever (60 seconds per prompt)
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(60, TimeUnit.SECONDS)  // Timeout after 60 seconds
                        .join();
                log.debug("[MAIN] All futures completed for prompt {}", totalPromptsProcessed);
            } catch (Exception e) {
                log.error("[MAIN] ✗ Timeout or error waiting for futures to complete for prompt {}: {}", 
                        totalPromptsProcessed, e.getMessage());
                // Continue processing - mark all as failed if timeout
                for (int i = 0; i < futures.size(); i++) {
                    if (!futures.get(i).isDone()) {
                        log.warn("[MAIN] Future {} for prompt {} did not complete in time", 
                                i, totalPromptsProcessed);
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
                    log.debug("[MAIN] Getting result from future for {}", serviceName);
                    // Add timeout to prevent hanging (should be ready, but add safety timeout)
                    AIModelResponse response = future.get(5, TimeUnit.SECONDS); // 5 second timeout
                    log.debug("[MAIN] Got response from {}: success={}", serviceName, response.isSuccess());
                    
                    // Process the response
                    if (response.isSuccess()) {
                        log.debug("[MAIN] Processing successful response from {}", serviceName);
                        try {
                            // Save the prompt and extract brand mentions from the response
                            // Use separate transaction to prevent one failure from aborting all
                            savePromptAndMentionsInNewTransaction(category, promptText, response, brands);
                            successCount++;
                            totalSuccessfulResponses++;
                            log.info("[MAIN] ✓ Successfully processed response from {} for prompt {}", 
                                    serviceName, totalPromptsProcessed);
                        } catch (org.springframework.dao.DataIntegrityViolationException e) {
                            // Database constraint violation - log and continue
                            log.error("[MAIN] ✗ Database constraint violation saving {} for prompt {}: {}", 
                                    serviceName, totalPromptsProcessed, e.getMessage());
                            log.error("[MAIN] This usually means the database constraint doesn't allow this AI model value. Check fix_constraint.sql");
                            failCount++;
                            totalFailedResponses++;
                        } catch (Exception e) {
                            // Other save errors - log and continue
                            log.error("[MAIN] ✗ Error saving {} for prompt {}: {}", 
                                    serviceName, totalPromptsProcessed, e.getMessage(), e);
                            failCount++;
                            totalFailedResponses++;
                        }
                    } else {
                        // Log the failure but continue with other services
                        String errorMsg = response.getErrorMessage() != null ? 
                                response.getErrorMessage() : "Unknown error";
                        log.warn("[MAIN] ✗ Failed to get response from {} for prompt {}. Error: {}", 
                                serviceName, totalPromptsProcessed, errorMsg);
                        failCount++;
                        totalFailedResponses++;
                    }
                } catch (TimeoutException e) {
                    // Future didn't complete in time
                    log.error("[MAIN] ✗ Timeout getting result from {} for prompt {} (future not ready)", 
                            serviceName, totalPromptsProcessed);
                    failCount++;
                    totalFailedResponses++;
                } catch (Exception e) {
                    // Catch any unexpected errors when getting future result
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
    
    /**
     * Save the AI prompt response and extract brand mentions from it
     * This method:
     * 1. Saves the prompt and AI response to the database
     * 2. Searches the response text for each brand name (case-insensitive)
     * 3. Creates Mention records for each brand found
     * 4. Saves any citations (URLs) associated with the mentions
     * 
     * @param category The category this prompt belongs to
     * @param promptText The original prompt/question sent to the AI
     * @param response The AI's response containing the answer
     * @param brands List of brands to search for in the response
     */
    /**
     * Save prompt and mentions in a new transaction to prevent one failure from aborting all
     * This ensures that if one save fails (e.g., constraint violation), other saves can still succeed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePromptAndMentionsInNewTransaction(Category category, String promptText, AIModelResponse response, List<Brand> brands) {
        savePromptAndMentions(category, promptText, response, brands);
    }
    
    private void savePromptAndMentions(Category category, String promptText, AIModelResponse response, List<Brand> brands) {
        String threadName = Thread.currentThread().getName();
        String modelName = response.getModel() != null ? response.getModel().getDisplayName() : "Unknown";
        long startTime = System.currentTimeMillis();
        
        log.info("[SAVE] [THREAD: {}] Saving prompt and extracting mentions for model: {}", threadName, modelName);
        log.debug("[SAVE] [THREAD: {}] Category: {}, Brands to check: {}", 
                threadName, category.getName(), brands.size());

        // Validate response has content
        if (response.getContent() == null || response.getContent().isEmpty()) {
            log.warn("[SAVE] [THREAD: {}] ✗ Response content is empty for model: {} - skipping save", 
                    threadName, modelName);
            return;
        }
        log.debug("[SAVE] [THREAD: {}] Response content length: {} chars", 
                threadName, response.getContent().length());

        // Step 1: Save the prompt and AI response to database
        log.debug("[SAVE] [THREAD: {}] Creating Prompt entity...", threadName);
        Prompt prompt = new Prompt();
        prompt.setCategory(category);
        prompt.setQueryText(promptText);
        prompt.setAiModel(response.getModel());
        prompt.setResponse(response.getContent());
        
        log.debug("[SAVE] [THREAD: {}] Saving Prompt to database...", threadName);
        prompt = promptRepository.save(prompt);
        log.info("[SAVE] [THREAD: {}] ✓ Saved prompt with ID: {} for model: {} (response length: {} chars)",
                threadName, prompt.getId(), modelName, response.getContent().length());

        // Step 2: Extract brand mentions from the response
        // Convert response to lowercase for case-insensitive matching
        log.debug("[SAVE] [THREAD: {}] Converting response to lowercase for brand matching...", threadName);
        String content = response.getContent().toLowerCase();
        int mentionCount = 0;
        List<String> mentionedBrands = new ArrayList<>();

        log.debug("[SAVE] [THREAD: {}] Checking {} brands for mentions...", threadName, brands.size());
        // Loop through each brand and check if it's mentioned in the response
        for (Brand brand : brands) {
            String brandName = brand.getName().toLowerCase();
            log.debug("[SAVE] [THREAD: {}] Checking for brand: {} (lowercase: {})", 
                    threadName, brand.getName(), brandName);
            
            // Check if brand name appears in the response text
            if (content.contains(brandName)) {
                log.debug("[SAVE] [THREAD: {}] ✓ Brand '{}' found in response!", threadName, brand.getName());
                
                // Brand was mentioned - create a Mention record
                log.debug("[SAVE] [THREAD: {}] Creating Mention entity for brand: {}", threadName, brand.getName());
                Mention mention = new Mention();
                mention.setPrompt(prompt);  // Link to the prompt
                mention.setBrand(brand);    // Link to the brand
                mention.setAiModel(response.getModel());  // Which AI model mentioned it
                mention.setContext(extractContext(content, brandName));  // Extract surrounding text
                mention.setSentiment("NEUTRAL"); // TODO: Implement sentiment analysis (POSITIVE/NEGATIVE/NEUTRAL)
                
                log.debug("[SAVE] [THREAD: {}] Saving Mention to database...", threadName);
                mention = mentionRepository.save(mention);
                mentionCount++;
                mentionedBrands.add(brand.getName());

                log.info("[SAVE] [THREAD: {}] ✓ Found and saved mention of brand '{}' in response from {}", 
                        threadName, brand.getName(), modelName);

                // Step 3: Save citations (URLs/sources) associated with this mention
                int citationCount = 0;
                if (response.getCitations() != null && !response.getCitations().isEmpty()) {
                    log.debug("[SAVE] [THREAD: {}] Processing {} citations for brand '{}'...", 
                            threadName, response.getCitations().size(), brand.getName());
                    for (Citation citation : response.getCitations()) {
                        citation.setMention(mention);  // Link citation to the mention
                        citation.setAiModel(response.getModel());
                        log.debug("[SAVE] [THREAD: {}] Saving citation: {}", threadName, citation.getSourceUrl());
                        citationRepository.save(citation);
                        citationCount++;
                    }
                    log.debug("[SAVE] [THREAD: {}] Saved {} citations for brand '{}' mention", 
                            threadName, citationCount, brand.getName());
                } else {
                    log.debug("[SAVE] [THREAD: {}] No citations found for brand '{}' mention from {}", 
                            threadName, brand.getName(), modelName);
                }
            } else {
                // Brand not mentioned in this response
                log.debug("[SAVE] [THREAD: {}] ✗ Brand '{}' not found in response from {}", 
                        threadName, brand.getName(), modelName);
            }
        }
        
        long saveTime = System.currentTimeMillis() - startTime;
        // Log summary of mentions found
        if (mentionCount > 0) {
            log.info("[SAVE] [THREAD: {}] ✓ Saved {} brand mention(s) for prompt from {} (took {} ms). Brands: {}", 
                    threadName, mentionCount, modelName, saveTime, mentionedBrands);
        } else {
            log.warn("[SAVE] [THREAD: {}] ⚠ No brand mentions found in response from {} for prompt: {} (took {} ms)", 
                    threadName, modelName, promptText.substring(0, Math.min(50, promptText.length())), saveTime);
        }
    }
    
    /**
     * Extract context around a brand mention (surrounding text)
     * This helps understand how the brand was mentioned in the AI response
     * 
     * @param content The full response text (lowercase)
     * @param brandName The brand name to find (lowercase)
     * @return A snippet of text around the brand mention (100 chars before and after)
     */
    private String extractContext(String content, String brandName) {
        int index = content.indexOf(brandName);
        if (index == -1) {
            return "";  // Brand not found
        }
        
        // Extract 100 characters before and after the brand name
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

