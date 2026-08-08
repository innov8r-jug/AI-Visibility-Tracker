package com.writesonic.visibility.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class CustomAnalysisResult {
    private String prompt;
    private int totalModelsQueried;
    private int successfulResponses;
    private long executionTimeMs;
    private Map<String, Object> analysisData;
}