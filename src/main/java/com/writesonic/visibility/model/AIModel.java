package com.writesonic.visibility.model;

import java.util.Arrays;

public enum AIModel {
    GEMINI("Gemini", "Google Gemini"),
    GROQ("Groq", "Groq AI"),
    WEB_CRAWL("WebCrawl", "Web UI Crawler"); // Not currently used

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

    public static AIModel fromCode(String code) {
        return Arrays.stream(AIModel.values())
                .filter(model -> model.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }
}

