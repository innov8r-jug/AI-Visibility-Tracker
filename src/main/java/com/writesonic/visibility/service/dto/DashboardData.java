package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardData {
    private DashboardMetrics metrics;
    private List<LeaderboardEntry> leaderboard;
    private Map<String, List<LeaderboardEntry>> leaderboardByModel;
    private List<BrandMetrics> brandMetrics;
    private List<TopCitedPage> topCitedPages;
    private Map<String, List<TopCitedPage>> topCitedPagesByModel;
    private Map<String, Map<String, Double>> modelComparison;
    private List<PromptDTO> prompts;
}

