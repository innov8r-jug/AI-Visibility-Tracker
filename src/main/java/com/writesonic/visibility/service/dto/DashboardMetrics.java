package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMetrics {
    private Long totalPrompts;
    private Integer brandsTracked;
    private Long totalMentions;
    private Long totalCitations;
    private List<String> modelsUsed;
}

