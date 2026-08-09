package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Canonical, flat shape for a custom-prompt analysis result. This is the single
 * contract the frontend reads from - no parallel maps, no ambiguous nesting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisData {
    private List<BrandResult> brands;
    private List<CitationResult> citations;
    /** AI model name -> list of brand names that model mentioned. */
    private Map<String, List<String>> modelComparison;
}
