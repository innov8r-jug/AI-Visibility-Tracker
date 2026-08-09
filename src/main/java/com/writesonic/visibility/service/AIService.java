package com.writesonic.visibility.service;

import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Citation;
import com.writesonic.visibility.service.dto.AIQueryResult;

import java.util.List;

public interface AIService {
    /**
     * Query the AI model with a prompt
     * @param prompt The prompt to send
     * @param category The category context
     * @return The AI response text plus any structured/grounded citations the provider
     *         itself returned (empty list if the provider doesn't support that)
     * @throws Exception If the query fails
     */
    AIQueryResult query(String prompt, String category) throws Exception;

    /**
     * Get the model name/identifier
     */
    String getModelName();

    /**
     * Get the model type enum
     */
    AIModel getModelType();

    /**
     * Check if the service is available (API key configured, etc.)
     */
    boolean isAvailable();

    /**
     * Extract citations from the AI response
     * @param response The AI response
     * @return List of citations (URLs, titles)
     */
    List<Citation> extractCitations(String response);
}

