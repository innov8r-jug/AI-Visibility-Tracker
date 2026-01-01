package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopCitedPage {
    private String url;
    private String title;
    private Long citationCount;
}

