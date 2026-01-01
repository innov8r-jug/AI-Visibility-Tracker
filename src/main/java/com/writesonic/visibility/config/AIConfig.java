package com.writesonic.visibility.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
public class AIConfig {
    
    @Value("${openai.api.key:}")
    private String openaiApiKey;
    
    @Value("${anthropic.api.key:}")
    private String anthropicApiKey;
    
    @Value("${google.api.key:}")
    private String googleApiKey;
    
    @Value("${perplexity.api.key:}")
    private String perplexityApiKey;
    
    @Value("${openai.api.url}")
    private String openaiApiUrl;
    
    @Value("${anthropic.api.url}")
    private String anthropicApiUrl;
    
    @Value("${google.api.url}")
    private String googleApiUrl;
    
    @Value("${perplexity.api.url}")
    private String perplexityApiUrl;
}

