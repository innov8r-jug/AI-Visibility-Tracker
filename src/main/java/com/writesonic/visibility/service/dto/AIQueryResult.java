package com.writesonic.visibility.service.dto;

import com.writesonic.visibility.model.Citation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of a single AIService.query() call: the assistant's answer text, plus any
 * "grounded" citations the provider itself returned as structured data (e.g. Gemini's
 * Grounding-with-Google-Search metadata) - as opposed to citations we merely scrape via
 * regex from a URL the model happened to type into its prose. groundedCitations is
 * empty for providers that don't support/return this (Groq, Cerebras, Cohere, or Gemini
 * when a given prompt didn't trigger a search).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIQueryResult {
    private String text;
    private List<Citation> groundedCitations;
}
