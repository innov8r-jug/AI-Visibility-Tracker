package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchResult {
    private String title;
    private String url;
    private String content;
}
