package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardEntry {
    private Long brandId;
    private String brandName;
    private Double visibilityScore;
    private Double citationShare;
    private Long totalMentions;
}

