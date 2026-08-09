package com.writesonic.visibility.dto;

import com.writesonic.visibility.service.dto.AnalysisData;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomAnalysisResult {
    private String prompt;
    private int totalModelsQueried;
    private int successfulResponses;
    private long executionTimeMs;
    private AnalysisData analysisData;
}