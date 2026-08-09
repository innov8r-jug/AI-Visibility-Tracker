package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated, cross-model view of a single brand for one custom-prompt analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandResult {
    private String name;
    private int mentionCount;
    private double sharePercent;
    private int citationCount;
    private String sentiment;
}
