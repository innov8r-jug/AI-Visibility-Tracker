package com.writesonic.visibility.service.dto;

import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Citation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIModelResponse {
    private String content;
    private AIModel model;
    private List<Citation> citations;
    private long responseTime;
    private boolean success;
    private String errorMessage;
    
    public static AIModelResponse failed(AIModel model, Throwable error) {
        return AIModelResponse.builder()
                .model(model)
                .success(false)
                .errorMessage(error.getMessage())
                .citations(new ArrayList<>())
                .build();
    }
    
    public static AIModelResponse success(AIModel model, String content, List<Citation> citations, long responseTime) {
        return AIModelResponse.builder()
                .model(model)
                .content(content)
                .citations(citations != null ? citations : new ArrayList<>())
                .responseTime(responseTime)
                .success(true)
                .build();
    }
}

