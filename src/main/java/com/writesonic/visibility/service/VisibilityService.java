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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
                            "\n- Keep the brand name in bold markdown (**Brand Name**)" +

                            "\n\nAdditionally, identify 3–7 notable competitors NOT in the provided list (relevant to this category)." +
                            "\nFor each competitor, follow the same bullet structure with official URL and 1–2 authoritative third-party sources." +
                            "\nOnly include real brands; skip anything uncertain or generic." +

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

        // Normalize and de-duplicate incoming brand names (case-insensitive)
        Set<String> normalizedInputBrands = new LinkedHashSet<>();
        for (String raw : brandNames) {
            String normalized = normalizeBrandName(raw);
            if (normalized != null && !normalized.isEmpty()) {
                normalizedInputBrands.add(normalized);
            }
        }

        List<Brand> brands = normalizedInputBrands.stream()
                .map(name -> brandRepository.findByNameIgnoreCaseAndCategoryId(name, categoryId)
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

        // Step 1.5: Discover and persist competitor brands from the AI response
        Set<String> existingBrandNames = brands.stream()
                .map(b -> b.getName().toLowerCase())
                .collect(Collectors.toSet());

        List<String> discoveredBrandNames = extractCompetitorBrands(response.getContent(), category.getName(), existingBrandNames);
        if (!discoveredBrandNames.isEmpty()) {
            log.info("[SAVE] [THREAD: {}] Discovered {} competitor brand(s) in response: {}", 
                    threadName, discoveredBrandNames.size(), discoveredBrandNames);

            List<Brand> discoveredBrands = new ArrayList<>();
            for (String name : discoveredBrandNames) {
                String normalized = normalizeBrandName(name);
                if (normalized == null || normalized.isEmpty()) {
                    continue;
                }

                Brand brand = brandRepository.findByNameIgnoreCaseAndCategoryId(normalized, category.getId())
                        .orElseGet(() -> {
                            Brand newBrand = new Brand();
                            newBrand.setName(normalized);
                            newBrand.setCategory(category);
                            try {
                                return brandRepository.save(newBrand);
                            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                                // Another thread may have created it concurrently; try to fetch
                                return brandRepository.findByNameIgnoreCaseAndCategoryId(normalized, category.getId())
                                        .orElse(null);
                            }
                        });

                if (brand != null) {
                    discoveredBrands.add(brand);
                    existingBrandNames.add(brand.getName().toLowerCase());
                }
            }

            if (!discoveredBrands.isEmpty()) {
                // Merge discovered brands into the working list for mention extraction
                List<Brand> merged = new ArrayList<>(brands);
                merged.addAll(discoveredBrands);
                brands = merged;
            }
        }

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
    
    /**
     * Extract competitor brand names from an AI response.
     * Uses structural patterns (bold, bullets, numbered, colon headings) and
     * filters false positives.
     */
    private List<String> extractCompetitorBrands(String responseContent, String categoryName, Set<String> existingBrandNames) {
        if (responseContent == null || responseContent.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> discovered = new LinkedHashSet<>();

        // Pattern 1: Markdown bold **Brand**
        Matcher boldMatcher = Pattern.compile("\\*\\*([^*]+)\\*\\*").matcher(responseContent);
        while (boldMatcher.find()) {
            String candidate = boldMatcher.group(1).trim();
            if (isValidBrandName(candidate, existingBrandNames)) {
                discovered.add(candidate);
            }
        }

        // Pattern 2: Bullets
        Matcher bulletMatcher = Pattern.compile("^[\\s]*[-•*]\\s+([A-Z][A-Za-z0-9\\s&.'-]{1,60}?)(?::|\\s|$)", Pattern.MULTILINE)
                .matcher(responseContent);
        while (bulletMatcher.find()) {
            String candidate = bulletMatcher.group(1).trim();
            if (isValidBrandName(candidate, existingBrandNames)) {
                discovered.add(candidate);
            }
        }

        // Pattern 3: Numbered list
        Matcher numberedMatcher = Pattern.compile("^[\\s]*\\d+[.)]\\s+([A-Z][A-Za-z0-9\\s&.'-]{1,60}?)(?::|\\s|$)", Pattern.MULTILINE)
                .matcher(responseContent);
        while (numberedMatcher.find()) {
            String candidate = numberedMatcher.group(1).trim();
            if (isValidBrandName(candidate, existingBrandNames)) {
                discovered.add(candidate);
            }
        }

        // Pattern 4: Heading with colon
        Matcher colonMatcher = Pattern.compile("^([A-Z][A-Za-z0-9\\s&.'-]{2,60}?):", Pattern.MULTILINE)
                .matcher(responseContent);
        while (colonMatcher.find()) {
            String candidate = colonMatcher.group(1).trim();
            if (isValidBrandName(candidate, existingBrandNames)) {
                discovered.add(candidate);
            }
        }

        log.debug("Extracted {} competitor brand candidates (pre-filter)", discovered.size());

        return discovered.stream()
                .map(String::trim)
                .filter(name -> !isFalsePositive(name))
                .collect(Collectors.toList());
    }

    /** Normalize brand name to Title Case (simple) */
    private String normalizeBrandName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";

        String[] parts = trimmed.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (part.length() == 1) {
                sb.append(part.toUpperCase());
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1).toLowerCase());
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private boolean isValidBrandName(String name, Set<String> existingBrandNames) {
        if (name == null) return false;
        String normalized = name.trim();
        if (normalized.length() < 2 || normalized.length() > 60) return false;
        if (!Character.isLetter(normalized.charAt(0))) return false;
        if (!normalized.matches(".*[A-Za-z].*")) return false;

        if (isFalsePositive(normalized)) return false;
        if (existingBrandNames.contains(normalized.toLowerCase())) return false;

        return true;
    }

    private boolean isFalsePositive(String name) {
        String lower = name.toLowerCase().trim();

        // AI model names / platforms
        String[] aiModels = {
                "gemini", "groq", "gpt", "claude", "perplexity", "openai", "anthropic",
                "google gemini", "google", "open ai", "chatgpt", "chat gpt"
        };
        for (String model : aiModels) {
            if (lower.equals(model) || lower.startsWith(model + " ") || lower.endsWith(" " + model)) {
                return true;
            }
        }

        String[] exactFalsePositives = {
                "official website", "website", "url", "link", "source", "reference",
                "authoritative", "third-party", "review", "comparison", "directory",
                "example", "note", "important", "please", "rules", "format",
                "brand name", "brand", "category", "tools", "software", "platform",
                "service", "solution", "product", "company", "organization",
                "best", "top", "popular", "leading", "major", "key", "main",
                "features", "benefits", "advantages", "disadvantages", "pros", "cons",
                "pricing", "cost", "free", "paid", "trial", "demo", "support",
                "documentation", "help", "guide", "tutorial", "faq", "about",
                "appears", "while", "prominence", "visibility", "search", "ai",
                "ai-generated", "ai-generated answers", "ai prominence", "ai search visibility",
                "generated", "answers", "answer", "question", "query", "prompt",
                "response", "content", "text", "data", "information", "details"
        };
        for (String fp : exactFalsePositives) {
            if (lower.equals(fp)) {
                return true;
            }
        }

        String[] containsFalsePositives = {
                "official website", "website url", "click here", "learn more",
                "read more", "see also", "for more", "additional information",
                "ai-generated", "ai search", "ai prominence", "search visibility",
                "appears in", "appears to", "while the", "while it", "while you",
                "prominence in", "visibility of", "search for", "ai model",
                "generated answer", "generated content", "answer to", "answer is"
        };
        for (String fp : containsFalsePositives) {
            if (lower.contains(fp)) {
                return true;
            }
        }

        // Too many common words
        String[] commonWords = {
                "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                "of", "with", "by", "from", "as", "is", "are", "was", "were",
                "this", "that", "these", "those", "what", "which", "who", "when",
                "where", "why", "how", "all", "each", "every", "some", "many",
                "more", "most", "other", "another", "such", "only", "just", "also",
                "very", "much", "well", "good", "best", "better", "great", "excellent"
        };
        String[] words = lower.split("\\s+");
        int commonCount = 0;
        for (String w : words) {
            for (String c : commonWords) {
                if (w.equals(c)) {
                    commonCount++;
                    break;
                }
            }
        }
        if (words.length > 1 && commonCount > words.length / 2) {
            return true;
        }
        if (words.length == 1) {
            for (String c : commonWords) {
                if (lower.equals(c)) {
                    return true;
                }
            }
        }

        // Numbers / symbols only
        if (name.matches("^\\d+$")) return true;
        if (name.matches("^[^A-Za-z0-9]+$")) return true;

        // Avoid sentence fragments starting with lowercase for multi-word
        if (words.length > 1 && Character.isLowerCase(name.charAt(0))) {
            return true;
        }

        String[] verbs = {
                "appears", "appear", "appearing", "while", "when", "where", "what",
                "which", "who", "how", "why", "is", "are", "was", "were", "be",
                "been", "being", "have", "has", "had", "do", "does", "did", "will",
                "would", "could", "should", "may", "might", "must", "can"
        };
        for (String v : verbs) {
            if (lower.equals(v) || lower.startsWith(v + " ") || lower.endsWith(" " + v)) {
                return true;
            }
        }

        return false;
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

