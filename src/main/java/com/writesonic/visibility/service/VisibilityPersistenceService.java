package com.writesonic.visibility.service;

import com.writesonic.visibility.model.*;
import com.writesonic.visibility.repository.*;
import com.writesonic.visibility.service.dto.AIModelResponse;
import com.writesonic.visibility.service.dto.BrandEvaluation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dedicated DB-write service for custom-prompt analyses. Called from
 * DynamicVisibilityService (a different bean) so the @Transactional proxy is
 * correctly engaged, and only ever after all network/LLM calls have already
 * completed - no HTTP calls happen inside this transactional method.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisibilityPersistenceService {

    private static final String CUSTOM_SEARCH_CATEGORY = "customSearch";

    private final PromptRepository promptRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;

    @Transactional
    public void saveCustomAnalysis(String userPrompt, List<AIModelResponse> responses, List<BrandEvaluation> evaluations) {
        log.info("Persisting analysis for prompt: '{}' ({} model response(s))", userPrompt, responses.size());

        Category category = categoryRepository.findByName(CUSTOM_SEARCH_CATEGORY)
                .orElseGet(() -> {
                    Category cat = new Category();
                    cat.setName(CUSTOM_SEARCH_CATEGORY);
                    cat.setDescription("User Custom Prompts");
                    return categoryRepository.save(cat);
                });

        Map<AIModel, List<BrandEvaluation>> mentionedByModel = evaluations.stream()
                .filter(BrandEvaluation::isMentioned)
                .collect(Collectors.groupingBy(BrandEvaluation::getAiModel));

        int totalMentionsPersisted = 0;

        for (AIModelResponse response : responses) {
            Prompt prompt = new Prompt();
            prompt.setCategory(category);
            prompt.setQueryText(userPrompt);
            prompt.setAiModel(response.getModel());
            prompt.setResponse(response.getContent());

            List<BrandEvaluation> mentionedForModel = mentionedByModel.getOrDefault(response.getModel(), List.of());
            List<Mention> mentionsForResponse = new ArrayList<>();

            for (BrandEvaluation eval : mentionedForModel) {
                Brand brand = findOrCreateBrand(eval.getBrand(), category);

                Mention mention = new Mention();
                mention.setPrompt(prompt);
                mention.setBrand(brand);
                mention.setAiModel(response.getModel());
                mention.setContext(eval.getContext());
                mention.setPosition(eval.getPosition());
                mention.setSentiment(eval.getSentiment());

                prompt.getMentions().add(mention);
                mentionsForResponse.add(mention);
                totalMentionsPersisted++;
            }

            // A response's citations aren't linked to any specific brand by the LLM, so
            // rather than duplicating every citation onto every mention (which both
            // inflates counts and multiplies DB rows), distribute them round-robin across
            // this response's mentions - each citation is persisted exactly once.
            if (response.getCitations() != null && !mentionsForResponse.isEmpty()) {
                int i = 0;
                for (Citation source : response.getCitations()) {
                    Mention target = mentionsForResponse.get(i % mentionsForResponse.size());
                    Citation citation = new Citation();
                    citation.setSourceUrl(source.getSourceUrl());
                    citation.setSourceTitle(source.getSourceTitle());
                    citation.setAiModel(response.getModel());
                    citation.setMention(target);
                    target.getCitations().add(citation);
                    i++;
                }
            }

            promptRepository.save(prompt);
        }

        log.info("Persisted {} prompt row(s) and {} mention(s) for prompt: '{}'",
                responses.size(), totalMentionsPersisted, userPrompt);
    }

    private Brand findOrCreateBrand(String name, Category category) {
        return brandRepository.findByNameIgnoreCaseAndCategoryId(name, category.getId())
                .orElseGet(() -> {
                    Brand brand = new Brand();
                    brand.setName(name);
                    brand.setCategory(category);
                    return brandRepository.save(brand);
                });
    }
}
