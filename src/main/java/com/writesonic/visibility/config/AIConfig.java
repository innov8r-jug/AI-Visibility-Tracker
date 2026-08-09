package com.writesonic.visibility.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
public class AIConfig {
    @Value("${google.api.key:}")
    private String googleApiKey;
    @Value("${groq.api.key:}")
    private String groqApiKey;
    @Value("${cerebras.api.key:}")
    private String cerebrasApiKey;
    @Value("${cohere.api.key:}")
    private String cohereApiKey;
    @Value("${google.api.url}")
    private String googleApiUrl;
    @Value("${groq.api.url}")
    private String groqApiUrl;
    @Value("${cerebras.api.url}")
    private String cerebrasApiUrl;
    @Value("${cerebras.api.model:llama-3.3-70b}")
    private String cerebrasApiModel;
    @Value("${cohere.api.url}")
    private String cohereApiUrl;
    @Value("${cohere.api.model:command-r-plus}")
    private String cohereApiModel;
    @Value("${tavily.api.key:}")
    private String tavilyApiKey;
    @Value("${tavily.api.url:https://api.tavily.com/search}")
    private String tavilyApiUrl;
}

