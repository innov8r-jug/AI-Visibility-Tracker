package com.writesonic.visibility.service;

import com.writesonic.visibility.model.*;
import com.writesonic.visibility.repository.*;
import com.writesonic.visibility.service.dto.AIModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * Generate prompts based on category
     */
    public List<String> generatePrompts(String category) {
        List<String> prompts = new ArrayList<>();
        prompts.add("What's the best " + category + " for businesses?");
        prompts.add("Compare top " + category + " tools and platforms");
        prompts.add("What are the top " + category + " recommendations?");
        prompts.add("List the best " + category + " solutions available");
        prompts.add("What " + category + " should I choose?");
        return prompts;
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
     */
    @Transactional
    public void analyzeVisibility(String categoryName, List<String> brandNames, List<AIModel> selectedModels) {
        // Get or create category
        Category category = categoryRepository.findByName(categoryName)
                .orElseGet(() -> {
                    Category cat = new Category();
                    cat.setName(categoryName);
                    return categoryRepository.save(cat);
                });
        
        // Get or create brands
        List<Brand> brands = brandNames.stream()
                .map(name -> brandRepository.findByName(name)
                        .orElseGet(() -> {
                            Brand brand = new Brand();
                            brand.setName(name);
                            brand.setCategory(category);
                            return brandRepository.save(brand);
                        }))
                .collect(Collectors.toList());
        
        // Get AI services
        List<AIService> services = selectedModels != null && !selectedModels.isEmpty()
                ? aiServiceFactory.getServicesByModels(selectedModels)
                : aiServiceFactory.getAllServices();
        
        // Generate prompts
        List<String> prompts = generatePrompts(categoryName);
        
        // Query all AI models for each prompt
        for (String promptText : prompts) {
            List<CompletableFuture<AIModelResponse>> futures = services.stream()
                    .map(service -> queryAIAsync(service, promptText, categoryName))
                    .collect(Collectors.toList());
            
            // Wait for all queries to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // Process responses
            for (CompletableFuture<AIModelResponse> future : futures) {
                AIModelResponse response = future.join();
                if (response.isSuccess()) {
                    savePromptAndMentions(category, promptText, response, brands);
                }
            }
        }
    }
    
    private void savePromptAndMentions(Category category, String promptText, AIModelResponse response, List<Brand> brands) {
        // Save prompt
        Prompt prompt = new Prompt();
        prompt.setCategory(category);
        prompt.setQueryText(promptText);
        prompt.setAiModel(response.getModel());
        prompt.setResponse(response.getContent());
        prompt = promptRepository.save(prompt);
        
        // Extract brand mentions
        String content = response.getContent().toLowerCase();
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
                
                // Save citations
                for (Citation citation : response.getCitations()) {
                    citation.setMention(mention);
                    citation.setAiModel(response.getModel());
                    citationRepository.save(citation);
                }
            }
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

