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
    @Value("${google.api.url}")
    private String googleApiUrl;
    @Value("${groq.api.url}")
    private String groqApiUrl;
}

