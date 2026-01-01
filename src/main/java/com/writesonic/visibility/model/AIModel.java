package com.writesonic.visibility.model;

public enum AIModel {
    GPT("GPT", "OpenAI GPT"),
    CLAUDE("Claude", "Anthropic Claude"),
    GEMINI("Gemini", "Google Gemini"),
    PERPLEXITY("Perplexity", "Perplexity AI"),
    WEB_CRAWL("Web Crawl", "Web UI Crawler");
    
    private final String code;
    private final String displayName;
    
    AIModel(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}

