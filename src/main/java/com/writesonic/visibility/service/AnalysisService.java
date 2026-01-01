package com.writesonic.visibility.service;

import com.writesonic.visibility.model.*;
import com.writesonic.visibility.repository.*;
import com.writesonic.visibility.service.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalysisService {
    
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final PromptRepository promptRepository;
    private final MentionRepository mentionRepository;
    private final CitationRepository citationRepository;
    
    /**
     * Get dashboard data for a category
     */
    public DashboardData getDashboardData(String categoryName) {
        Category category = categoryRepository.findByName(categoryName)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryName));
        
        List<Brand> brands = brandRepository.findByCategory(category);
        List<Prompt> prompts = promptRepository.findByCategory(category);
        
        // Calculate overall metrics
        long totalPrompts = prompts.size();
        long totalMentions = brands.stream()
                .mapToLong(brand -> mentionRepository.countByBrand(brand))
                .sum();
        
        // Calculate per-brand metrics
        List<BrandMetrics> brandMetricsList = brands.stream()
                .map(brand -> calculateBrandMetrics(brand, prompts.size()))
                .collect(Collectors.toList());
        
        // Generate leaderboard
        List<LeaderboardEntry> leaderboard = generateLeaderboard(brandMetricsList);
        
        // Get top cited pages
        List<TopCitedPage> topCitedPages = getTopCitedPages();
        
        // Model comparison
        Map<String, Map<String, Double>> modelComparison = generateModelComparison(brands);
        
        // Per-model leaderboards
        Map<String, List<LeaderboardEntry>> leaderboardByModel = generatePerModelLeaderboards(brands, prompts);
        
        // Per-model top cited pages
        Map<String, List<TopCitedPage>> topCitedPagesByModel = generatePerModelTopCitedPages();
        
        return DashboardData.builder()
                .metrics(DashboardMetrics.builder()
                        .totalPrompts(totalPrompts)
                        .brandsTracked(brands.size())
                        .totalMentions(totalMentions)
                        .modelsUsed(getAvailableModels())
                        .build())
                .leaderboard(leaderboard)
                .leaderboardByModel(leaderboardByModel)
                .brandMetrics(brandMetricsList)
                .topCitedPages(topCitedPages)
                .topCitedPagesByModel(topCitedPagesByModel)
                .modelComparison(modelComparison)
                .prompts(prompts.stream()
                        .map(this::mapPromptToDTO)
                        .collect(Collectors.toList()))
                .build();
    }
    
    private BrandMetrics calculateBrandMetrics(Brand brand, long totalPrompts) {
        long totalMentions = mentionRepository.countByBrand(brand);
        double visibilityScore = totalPrompts > 0 ? (double) totalMentions / totalPrompts * 100 : 0;
        
        // Calculate per-model mentions
        Map<String, Long> mentionsByModel = new HashMap<>();
        for (AIModel model : AIModel.values()) {
            long count = mentionRepository.countByBrandAndAiModel(brand, model);
            if (count > 0) {
                mentionsByModel.put(model.getCode(), count);
            }
        }
        
        // Calculate citation share (simplified - would need total category mentions)
        double citationShare = calculateCitationShare(brand);
        
        return BrandMetrics.builder()
                .brandId(brand.getId())
                .brandName(brand.getName())
                .visibilityScore(visibilityScore)
                .totalMentions(totalMentions)
                .citationShare(citationShare)
                .mentionsByModel(mentionsByModel)
                .build();
    }
    
    private double calculateCitationShare(Brand brand) {
        long brandMentions = mentionRepository.countByBrand(brand);
        Category category = brand.getCategory();
        List<Brand> allBrands = brandRepository.findByCategory(category);
        long totalCategoryMentions = allBrands.stream()
                .mapToLong(b -> mentionRepository.countByBrand(b))
                .sum();
        
        return totalCategoryMentions > 0 ? (double) brandMentions / totalCategoryMentions * 100 : 0;
    }
    
    private List<LeaderboardEntry> generateLeaderboard(List<BrandMetrics> brandMetrics) {
        return brandMetrics.stream()
                .sorted(Comparator.comparing(BrandMetrics::getVisibilityScore).reversed())
                .map(metrics -> LeaderboardEntry.builder()
                        .brandId(metrics.getBrandId())
                        .brandName(metrics.getBrandName())
                        .visibilityScore(metrics.getVisibilityScore())
                        .citationShare(metrics.getCitationShare())
                        .totalMentions(metrics.getTotalMentions())
                        .build())
                .collect(Collectors.toList());
    }
    
    private Map<String, List<LeaderboardEntry>> generatePerModelLeaderboards(List<Brand> brands, List<Prompt> prompts) {
        Map<String, List<LeaderboardEntry>> result = new HashMap<>();
        
        for (AIModel model : AIModel.values()) {
            List<BrandMetrics> modelMetrics = brands.stream()
                    .map(brand -> {
                        long mentions = mentionRepository.countByBrandAndAiModel(brand, model);
                        long modelPrompts = prompts.stream()
                                .filter(p -> p.getAiModel() == model)
                                .count();
                        double visibility = modelPrompts > 0 ? (double) mentions / modelPrompts * 100 : 0;
                        
                        return BrandMetrics.builder()
                                .brandId(brand.getId())
                                .brandName(brand.getName())
                                .visibilityScore(visibility)
                                .totalMentions(mentions)
                                .build();
                    })
                    .filter(m -> m.getTotalMentions() > 0)
                    .sorted(Comparator.comparing(BrandMetrics::getVisibilityScore).reversed())
                    .collect(Collectors.toList());
            
            List<LeaderboardEntry> entries = modelMetrics.stream()
                    .map(m -> LeaderboardEntry.builder()
                            .brandId(m.getBrandId())
                            .brandName(m.getBrandName())
                            .visibilityScore(m.getVisibilityScore())
                            .totalMentions(m.getTotalMentions())
                            .build())
                    .collect(Collectors.toList());
            
            if (!entries.isEmpty()) {
                result.put(model.getCode(), entries);
            }
        }
        
        return result;
    }
    
    private Map<String, Map<String, Double>> generateModelComparison(List<Brand> brands) {
        Map<String, Map<String, Double>> comparison = new HashMap<>();
        
        for (Brand brand : brands) {
            Map<String, Double> modelScores = new HashMap<>();
            List<Prompt> allPrompts = promptRepository.findAll();
            
            for (AIModel model : AIModel.values()) {
                long mentions = mentionRepository.countByBrandAndAiModel(brand, model);
                long modelPrompts = allPrompts.stream()
                        .filter(p -> p.getAiModel() == model)
                        .count();
                double score = modelPrompts > 0 ? (double) mentions / modelPrompts * 100 : 0;
                modelScores.put(model.getCode(), score);
            }
            
            comparison.put(brand.getName(), modelScores);
        }
        
        return comparison;
    }
    
    private List<TopCitedPage> getTopCitedPages() {
        List<Object[]> results = citationRepository.findTopCitedPages();
        return results.stream()
                .limit(20)
                .map(row -> TopCitedPage.builder()
                        .url((String) row[0])
                        .title((String) row[1])
                        .citationCount(((Number) row[2]).longValue())
                        .build())
                .collect(Collectors.toList());
    }
    
    private Map<String, List<TopCitedPage>> generatePerModelTopCitedPages() {
        Map<String, List<TopCitedPage>> result = new HashMap<>();
        
        for (AIModel model : AIModel.values()) {
            List<Object[]> modelResults = citationRepository.findTopCitedPagesByModel(model);
            List<TopCitedPage> pages = modelResults.stream()
                    .limit(10)
                    .map(row -> TopCitedPage.builder()
                            .url((String) row[0])
                            .title((String) row[1])
                            .citationCount(((Number) row[2]).longValue())
                            .build())
                    .collect(Collectors.toList());
            
            if (!pages.isEmpty()) {
                result.put(model.getCode(), pages);
            }
        }
        
        return result;
    }
    
    private PromptDTO mapPromptToDTO(Prompt prompt) {
        List<Mention> mentions = prompt.getMentions();
        
        List<String> mentionedBrands = mentions.stream()
                .map(m -> m.getBrand().getName())
                .distinct()
                .collect(Collectors.toList());
        
        return PromptDTO.builder()
                .id(prompt.getId())
                .queryText(prompt.getQueryText())
                .aiModel(prompt.getAiModel().getCode())
                .response(prompt.getResponse())
                .timestamp(prompt.getTimestamp())
                .mentionedBrands(mentionedBrands)
                .build();
    }
    
    private List<String> getAvailableModels() {
        return Arrays.stream(AIModel.values())
                .map(AIModel::getCode)
                .collect(Collectors.toList());
    }
}

