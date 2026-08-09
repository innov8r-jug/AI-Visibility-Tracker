package com.writesonic.visibility.dto;

import lombok.Data;
import java.util.List;

@Data
public class CustomPromptRequest {
    private String userPrompt;
    private List<String> targetBrands;
    private List<String> aiModels;
}