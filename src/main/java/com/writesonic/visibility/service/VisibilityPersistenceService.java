package com.writesonic.visibility.service;

import com.writesonic.visibility.model.*;
import com.writesonic.visibility.repository.*;
import com.writesonic.visibility.service.dto.AIModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VisibilityPersistenceService {

    private final PromptRepository promptRepository;
    private final CategoryRepository categoryRepository;

    @Transactional
    public void saveCustomAnalysis(String userPrompt, List<AIModelResponse> responses) {
        log.info("Asynchronously persisting data for prompt: {}", userPrompt);

        // Map to a "Custom" category for untracked searches
        Category category = categoryRepository.findByName("customSearch")
                .orElseGet(() -> {
                    Category cat = new Category();
                    cat.setName("customSearch");
                    cat.setDescription("User Custom Prompts");
                    return categoryRepository.save(cat);
                });

        for (AIModelResponse response : responses) {
            Prompt prompt = new Prompt();
            prompt.setCategory(category);
            prompt.setQueryText(userPrompt);
            prompt.setAiModel(response.getModel());
            prompt.setResponse(response.getContent());

            promptRepository.save(prompt);
            // Mention and Citation extraction/saving logic goes here...
        }

        log.info("Successfully persisted async analysis data.");
    }
}