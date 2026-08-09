package com.writesonic.visibility.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writesonic.visibility.dto.CustomAnalysisResult;
import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Citation;
import com.writesonic.visibility.service.dto.AIModelResponse;
import com.writesonic.visibility.service.dto.AIQueryResult;
import com.writesonic.visibility.service.dto.AnalysisData;
import com.writesonic.visibility.service.dto.BrandEvaluation;
import com.writesonic.visibility.service.dto.BrandResult;
import com.writesonic.visibility.service.dto.CitationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Orchestrates the "scatter-gather" flow for a free-form prompt: queries every
 * available/selected AI model in parallel, extracts per-brand evaluations from
 * each model's structured JSON output, aggregates them into the response the
 * user sees immediately, and fires off (non-blocking) persistence of the raw
 * data in the background.
 */
@Service
@Slf4j
public class DynamicVisibilityService {

    private final AIServiceFactory aiServiceFactory;
    private final VisibilityPersistenceService persistenceService;
    private final Executor aiQueryExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final long PER_CALL_TIMEOUT_SECONDS = 45;
    private static final int MAX_OPEN_ENDED_BRAND_NAME_LENGTH = 60;

    /**
     * Generic prompt-engineering instruction applied ahead of every LLM call, regardless
     * of what the user actually asked. Two goals: (1) push the model to naturally mention
     * multiple real brands/products where relevant, so there's something to compare in the
     * first place, instead of a single-answer response with nothing to benchmark; (2) get
     * real, verifiable source URLs into the prose (which the existing regex citation
     * extractor picks up) without ever fabricating links.
     */
    private static final String DIVERSITY_AND_CITATION_INSTRUCTION =
            "When answering, be comprehensive: where genuinely relevant to the question, mention multiple " +
            "distinct real brands, products, or tools (not just one) so a comparative view is possible - this " +
            "guidance applies to any kind of question, not only product-recommendation ones.\n" +
            "Where you are confident of a real, correct source (an official brand website, documentation, or a " +
            "well-known review platform such as G2, Capterra, or Trustpilot), include its URL directly in your " +
            "answer as you mention it. Never invent or guess a URL - it is better to omit a citation than to " +
            "fabricate one.\n\n";

    public DynamicVisibilityService(AIServiceFactory aiServiceFactory,
                                     VisibilityPersistenceService persistenceService,
                                     @Qualifier("aiQueryExecutor") Executor aiQueryExecutor) {
        this.aiServiceFactory = aiServiceFactory;
        this.persistenceService = persistenceService;
        this.aiQueryExecutor = aiQueryExecutor;
    }

    public CustomAnalysisResult analyzeCustomPrompt(String userPrompt, List<String> targetBrands, List<AIModel> selectedModels) {
        long startTime = System.currentTimeMillis();

        List<String> brands = normalizeTargetBrands(targetBrands);

        List<AIService> services = (selectedModels == null || selectedModels.isEmpty())
                ? aiServiceFactory.getAllServices()
                : aiServiceFactory.getServicesByModels(selectedModels);

        if (services.isEmpty()) {
            throw new IllegalStateException("No AI services available. Check API keys.");
        }

        List<CompletableFuture<AIModelResponse>> futures = services.stream()
                .map(service -> CompletableFuture
                        .supplyAsync(() -> executeModelCall(service, userPrompt, brands), aiQueryExecutor)
                        .orTimeout(PER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.error("[{}] call failed or timed out", service.getModelType(), ex);
                            return AIModelResponse.failed(service.getModelType(), ex);
                        }))
                .collect(Collectors.toList());

        // Safe to join() here: every future above is guaranteed to resolve (success or
        // typed failure) via orTimeout/exceptionally, so allOf can never throw.
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<AIModelResponse> allResponses = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        List<AIModelResponse> successful = allResponses.stream()
                .filter(AIModelResponse::isSuccess)
                .collect(Collectors.toList());

        List<BrandEvaluation> evaluations = extractBrandEvaluations(successful, brands);
        AnalysisData analysisData = buildAnalysisData(successful, evaluations);

        // Fire-and-forget relative to the HTTP response (DB writes must never block the
        // user-facing latency), but failures are now observed/logged instead of vanishing.
        CompletableFuture.runAsync(() -> persistenceService.saveCustomAnalysis(userPrompt, successful, evaluations), aiQueryExecutor)
                .exceptionally(ex -> {
                    log.error("Failed to persist custom analysis for prompt: '{}'", userPrompt, ex);
                    return null;
                });

        return CustomAnalysisResult.builder()
                .prompt(userPrompt)
                .totalModelsQueried(services.size())
                .successfulResponses(successful.size())
                .executionTimeMs(System.currentTimeMillis() - startTime)
                .analysisData(analysisData)
                .build();
    }

    private List<String> normalizeTargetBrands(List<String> targetBrands) {
        if (targetBrands == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String brand : targetBrands) {
            if (brand == null) continue;
            String trimmed = brand.trim();
            if (trimmed.isEmpty()) continue;
            if (seen.add(trimmed.toLowerCase())) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private AIModelResponse executeModelCall(AIService service, String userPrompt, List<String> targetBrands) {
        long callStart = System.currentTimeMillis();
        try {
            String augmentedPrompt = buildAugmentedPrompt(userPrompt, targetBrands);
            AIQueryResult result = service.query(augmentedPrompt, "Custom Search");

            // Prefer the provider's own grounded/searched sources (a real research trail)
            // over the regex text-scrape fallback, which only catches a URL the model
            // happened to type from memory - not evidence it actually looked anything up.
            List<Citation> groundedCitations = result.getGroundedCitations();
            List<Citation> citations = (groundedCitations != null && !groundedCitations.isEmpty())
                    ? groundedCitations
                    : service.extractCitations(result.getText());
            citations.forEach(c -> c.setAiModel(service.getModelType()));

            return AIModelResponse.success(service.getModelType(), result.getText(), citations, System.currentTimeMillis() - callStart);
        } catch (Exception e) {
            return AIModelResponse.failed(service.getModelType(), e);
        }
    }

    /**
     * Wraps the user's prompt with an instruction to append a strict, parseable JSON
     * block evaluating brands. This replaces the old approach of regex-mining the
     * model's prose for bolded text (which misclassified generic phrases as brands) -
     * the model is now asked to fill in a fixed schema instead of inventing names.
     */
    private String buildAugmentedPrompt(String userPrompt, List<String> targetBrands) {
        StringBuilder sb = new StringBuilder(userPrompt);
        sb.append("\n\n---\n");
        sb.append(DIVERSITY_AND_CITATION_INSTRUCTION);
        if (!targetBrands.isEmpty()) {
            sb.append("After answering the question above, do two things:\n")
                    .append("1. Evaluate these exact brands: ")
                    .append(String.join(", ", targetBrands))
                    .append(". For each one, determine whether it was mentioned in your answer above, its approximate rank/position if mentioned, and the overall sentiment.\n")
                    .append("2. Separately, list any OTHER real brand/product names (competitors) you mentioned in your answer that are NOT in the list above - this is for competitive benchmarking, so do not skip this step.\n")
                    .append("Append, as the VERY LAST thing in your response, a single JSON object in exactly this shape (no markdown code fences, no text after it):\n")
                    .append("{\"brand_evaluations\":[{\"brand\":\"<exact brand name from the list>\",\"mentioned\":true|false,\"position\":<integer or null>,\"sentiment\":\"positive|neutral|negative\",\"context\":\"<one short sentence>\"}],")
                    .append("\"competitor_brands\":[{\"brand\":\"<other brand name>\",\"position\":<integer or null>,\"sentiment\":\"positive|neutral|negative\",\"context\":\"<one short sentence>\"}]}\n")
                    .append("Include exactly one object per brand listed in part 1, in the same order as given. ")
                    .append("For competitor_brands, include every other distinct real brand/product name mentioned (empty array if none) - do not include generic descriptive phrases, feature names, or section headings.");
        } else {
            sb.append("After answering the question above, list the distinct real brand or product names you mentioned. ")
                    .append("Do not include generic descriptive phrases, feature names, or section headings - only actual brand/product names.\n")
                    .append("Append, as the VERY LAST thing in your response, a single JSON object in exactly this shape (no markdown code fences, no text after it):\n")
                    .append("{\"brands\":[\"Brand One\",\"Brand Two\"]}\n")
                    .append("If no specific brand names were mentioned, return {\"brands\":[]}.");
        }
        return sb.toString();
    }

    private List<BrandEvaluation> extractBrandEvaluations(List<AIModelResponse> responses, List<String> targetBrands) {
        List<BrandEvaluation> evaluations = new ArrayList<>();

        for (AIModelResponse response : responses) {
            String content = response.getContent();
            if (content == null) continue;

            if (!targetBrands.isEmpty()) {
                evaluations.addAll(extractTargetedEvaluations(content, response.getModel(), targetBrands));
            } else {
                evaluations.addAll(extractOpenEndedEvaluations(content, response.getModel()));
            }
        }
        return evaluations;
    }

    /**
     * Target-brand mode still discovers competitors: the model is asked both to
     * evaluate the caller's exact brand list AND to separately name any other real
     * brands it mentioned (competitor_brands). Without this, "track Salesforce"
     * would only ever report on Salesforce and never show who it's competing
     * against - useless for a visibility/competitive-benchmarking tool.
     */
    private List<BrandEvaluation> extractTargetedEvaluations(String content, AIModel model, List<String> targetBrands) {
        List<BrandEvaluation> result = new ArrayList<>();
        JsonNode root = extractBalancedJson(content, "brand_evaluations");

        if (root != null && root.get("brand_evaluations") != null && root.get("brand_evaluations").isArray()) {
            Set<String> covered = new LinkedHashSet<>();
            for (JsonNode node : root.get("brand_evaluations")) {
                String rawBrand = node.hasNonNull("brand") ? node.get("brand").asText().trim() : "";
                String canonical = matchCanonicalBrand(rawBrand, targetBrands);
                if (canonical == null) continue;

                boolean mentioned = node.hasNonNull("mentioned") && node.get("mentioned").asBoolean(false);
                Integer position = (node.hasNonNull("position") && node.get("position").isInt()) ? node.get("position").asInt() : null;
                String sentiment = node.hasNonNull("sentiment") ? node.get("sentiment").asText() : "neutral";
                String context = node.hasNonNull("context") ? node.get("context").asText() : null;

                result.add(BrandEvaluation.builder()
                        .brand(canonical)
                        .aiModel(model)
                        .mentioned(mentioned)
                        .position(position)
                        .sentiment(sentiment)
                        .context(context)
                        .build());
                covered.add(canonical.toLowerCase());
            }
            // Fill in any target brands the model's JSON omitted, so callers can rely on
            // getting an evaluation entry (even if unmentioned) per input brand per model.
            for (String brand : targetBrands) {
                if (!covered.contains(brand.toLowerCase())) {
                    result.add(fallbackSubstringEvaluation(content, model, brand));
                }
            }
            result.addAll(extractCompetitorEvaluations(root, model, targetBrands));
            return result;
        }

        log.warn("[{}] Could not parse structured brand_evaluations JSON; falling back to substring match against user-supplied target brands only (competitor discovery skipped for this response).", model);
        for (String brand : targetBrands) {
            result.add(fallbackSubstringEvaluation(content, model, brand));
        }
        return result;
    }

    private List<BrandEvaluation> extractCompetitorEvaluations(JsonNode root, AIModel model, List<String> targetBrands) {
        List<BrandEvaluation> result = new ArrayList<>();
        JsonNode competitors = root.get("competitor_brands");
        if (competitors == null || !competitors.isArray()) {
            return result;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : competitors) {
            String rawBrand = node.hasNonNull("brand") ? node.get("brand").asText().trim()
                    : (node.isTextual() ? node.asText("").trim() : "");
            if (rawBrand.isEmpty() || rawBrand.length() > MAX_OPEN_ENDED_BRAND_NAME_LENGTH) continue;
            // Skip anything that duplicates a target brand - it's already covered above.
            if (matchCanonicalBrand(rawBrand, targetBrands) != null) continue;
            if (!seen.add(rawBrand.toLowerCase())) continue;

            Integer position = (node.hasNonNull("position") && node.get("position").isInt()) ? node.get("position").asInt() : null;
            String sentiment = node.hasNonNull("sentiment") ? node.get("sentiment").asText() : "neutral";
            String context = node.hasNonNull("context") ? node.get("context").asText() : null;

            result.add(BrandEvaluation.builder()
                    .brand(rawBrand)
                    .aiModel(model)
                    .mentioned(true)
                    .position(position)
                    .sentiment(sentiment)
                    .context(context)
                    .build());
        }
        return result;
    }

    /**
     * Last-resort fallback for target-brand mode only: a plain case-insensitive
     * substring check against a brand name the USER explicitly supplied. This is safe
     * (unlike the old regex/blocklist heuristic) because it never invents brand names -
     * it only checks for the presence of names already trusted by the caller.
     */
    private BrandEvaluation fallbackSubstringEvaluation(String content, AIModel model, String brand) {
        boolean mentioned = content.toLowerCase().contains(brand.toLowerCase());
        return BrandEvaluation.builder()
                .brand(brand)
                .aiModel(model)
                .mentioned(mentioned)
                .position(null)
                .sentiment("neutral")
                .context(null)
                .build();
    }

    private String matchCanonicalBrand(String candidate, List<String> targetBrands) {
        if (candidate.isEmpty()) return null;
        for (String target : targetBrands) {
            if (target.equalsIgnoreCase(candidate)) {
                return target;
            }
        }
        return null;
    }

    private List<BrandEvaluation> extractOpenEndedEvaluations(String content, AIModel model) {
        List<BrandEvaluation> result = new ArrayList<>();
        JsonNode root = extractBalancedJson(content, "brands");

        if (root == null || root.get("brands") == null || !root.get("brands").isArray()) {
            log.warn("[{}] No structured 'brands' JSON found in open-ended response; skipping brand extraction for this response (no unreliable text-mining fallback).", model);
            return result;
        }

        int position = 1;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : root.get("brands")) {
            String name = node.asText("").trim();
            if (name.isEmpty() || name.length() > MAX_OPEN_ENDED_BRAND_NAME_LENGTH) continue;
            if (!seen.add(name.toLowerCase())) continue;

            result.add(BrandEvaluation.builder()
                    .brand(name)
                    .aiModel(model)
                    .mentioned(true)
                    .position(position++)
                    .sentiment("neutral")
                    .context(null)
                    .build());
        }
        return result;
    }

    /**
     * Finds a JSON object in free-form text that contains the given key, tolerating
     * prose before/after it. Walks backward from the key's location to find a
     * balanced-brace object boundary, since LLMs often don't put the JSON block in
     * complete isolation despite instructions.
     */
    private JsonNode extractBalancedJson(String content, String key) {
        if (content == null) return null;
        String marker = "\"" + key + "\"";
        int keyIdx = content.indexOf(marker);
        if (keyIdx < 0) return null;

        int start = content.lastIndexOf('{', keyIdx);
        while (start >= 0) {
            int depth = 0;
            for (int i = start; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String candidate = content.substring(start, i + 1);
                        try {
                            JsonNode node = objectMapper.readTree(candidate);
                            if (node.has(key)) {
                                return node;
                            }
                        } catch (Exception ignored) {
                            // not valid JSON from this start point; try an earlier '{'
                        }
                        break;
                    }
                }
            }
            start = start == 0 ? -1 : content.lastIndexOf('{', start - 1);
        }
        return null;
    }

    private AnalysisData buildAnalysisData(List<AIModelResponse> responses, List<BrandEvaluation> evaluations) {
        int totalModels = responses.size();

        Map<String, List<BrandEvaluation>> mentionedByBrand = evaluations.stream()
                .filter(BrandEvaluation::isMentioned)
                .collect(Collectors.groupingBy(BrandEvaluation::getBrand, LinkedHashMap::new, Collectors.toList()));

        Map<String, Integer> citationCountByBrand = distributeCitationsAcrossBrands(responses, evaluations);

        List<BrandResult> brandResults = new ArrayList<>();
        for (Map.Entry<String, List<BrandEvaluation>> entry : mentionedByBrand.entrySet()) {
            List<BrandEvaluation> evals = entry.getValue();
            int mentionCount = evals.size();
            double share = totalModels == 0 ? 0.0 : (mentionCount * 100.0 / totalModels);

            brandResults.add(BrandResult.builder()
                    .name(entry.getKey())
                    .mentionCount(mentionCount)
                    .sharePercent(Math.round(share * 10.0) / 10.0)
                    .citationCount(citationCountByBrand.getOrDefault(entry.getKey(), 0))
                    .sentiment(majoritySentiment(evals))
                    .build());
        }
        brandResults.sort(Comparator.comparingDouble(BrandResult::getSharePercent).reversed());

        Map<String, CitationAggregate> citationsByUrl = new LinkedHashMap<>();
        for (AIModelResponse response : responses) {
            if (response.getCitations() == null) continue;
            for (Citation citation : response.getCitations()) {
                if (citation.getSourceUrl() == null) continue;
                citationsByUrl.computeIfAbsent(citation.getSourceUrl(),
                        url -> new CitationAggregate(url, citation.getSourceTitle())).count++;
            }
        }
        List<CitationResult> citationResults = citationsByUrl.values().stream()
                .map(agg -> CitationResult.builder().url(agg.url).title(agg.title).count(agg.count).build())
                .sorted(Comparator.comparingInt(CitationResult::getCount).reversed())
                .collect(Collectors.toList());

        Map<String, List<String>> modelComparison = evaluations.stream()
                .filter(BrandEvaluation::isMentioned)
                .collect(Collectors.groupingBy(
                        e -> e.getAiModel().name(),
                        LinkedHashMap::new,
                        Collectors.mapping(BrandEvaluation::getBrand, Collectors.toList())));

        return AnalysisData.builder()
                .brands(brandResults)
                .citations(citationResults)
                .modelComparison(modelComparison)
                .build();
    }

    /**
     * A model's response text doesn't tell us which of its citations belongs to which
     * brand - the LLM isn't asked to link them. Crediting every citation in a response
     * to every brand that response mentioned (the old approach) inflates totals: if a
     * response mentions 5 brands and has 5 citations, that's 25 "citation credits", not
     * 5. Instead we split each response's citations evenly across the brands it
     * mentioned, so summing citationCount across brands stays close to the real total
     * citations extracted, rather than ballooning with every extra brand mentioned.
     */
    private Map<String, Integer> distributeCitationsAcrossBrands(List<AIModelResponse> responses, List<BrandEvaluation> evaluations) {
        Map<AIModel, Set<String>> mentionedBrandsByModel = evaluations.stream()
                .filter(BrandEvaluation::isMentioned)
                .collect(Collectors.groupingBy(BrandEvaluation::getAiModel,
                        Collectors.mapping(BrandEvaluation::getBrand, Collectors.toSet())));

        Map<String, Integer> citationCountByBrand = new LinkedHashMap<>();
        for (AIModelResponse response : responses) {
            int citationsInResponse = response.getCitations() == null ? 0 : response.getCitations().size();
            if (citationsInResponse == 0) continue;

            Set<String> brandsInResponse = mentionedBrandsByModel.getOrDefault(response.getModel(), Set.of());
            if (brandsInResponse.isEmpty()) continue;

            int fairShare = citationsInResponse / brandsInResponse.size();
            for (String brand : brandsInResponse) {
                citationCountByBrand.merge(brand, fairShare, Integer::sum);
            }
        }
        return citationCountByBrand;
    }

    private String majoritySentiment(List<BrandEvaluation> evals) {
        Map<String, Long> counts = evals.stream()
                .map(e -> e.getSentiment() == null ? "neutral" : e.getSentiment())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<String> winners = counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return winners.size() == 1 ? winners.get(0) : "mixed";
    }

    private static class CitationAggregate {
        final String url;
        final String title;
        int count = 0;

        CitationAggregate(String url, String title) {
            this.url = url;
            this.title = title;
        }
    }
}
