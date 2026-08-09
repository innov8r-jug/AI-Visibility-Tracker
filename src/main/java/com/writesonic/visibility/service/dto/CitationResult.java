package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single cited source URL, deduplicated and counted across all successful
 * model responses for one custom-prompt analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationResult {
    private String url;
    private String title;
    private int count;
}
