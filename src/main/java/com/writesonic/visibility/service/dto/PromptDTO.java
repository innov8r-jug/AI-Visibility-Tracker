package com.writesonic.visibility.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptDTO {
    private Long id;
    private String queryText;
    private String aiModel;
    private String response;
    private LocalDateTime timestamp;
    private List<String> mentionedBrands;
}

