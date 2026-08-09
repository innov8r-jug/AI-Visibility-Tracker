package com.writesonic.visibility.service.dto;

import com.writesonic.visibility.model.AIModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single brand's evaluation within one AI model's response, produced by the
 * model itself via the structured JSON block appended to its answer (see
 * DynamicVisibilityService#buildAugmentedPrompt). This is the one artifact that
 * flows from extraction -> API response (AnalysisData) -> DB persistence
 * (Mention/Citation rows), so all three stay consistent with each other.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandEvaluation {
    private String brand;
    private AIModel aiModel;
    private boolean mentioned;
    private Integer position;
    private String sentiment;
    private String context;
}
