package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandMetrics {
    private Long brandId;
    private String brandName;
    private Double visibilityScore;
    private Long totalMentions;
    private Double citationShare;
    private Map<String, Long> mentionsByModel;
}

