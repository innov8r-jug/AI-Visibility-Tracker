package com.writesonic.visibility.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Category-specific prompts for better AI query results
 */
public class CategoryPrompts {

    private static final Map<String, List<String>> CATEGORY_PROMPTS = new HashMap<>();

    static {
        // CRM Software prompts
        CATEGORY_PROMPTS.put("CRM Software", List.of(
            "What are the best CRM software solutions for businesses?",
            "Compare top CRM platforms for customer relationship management",
            "What CRM tools are recommended for small businesses?",
            "List the best CRM software for sales teams",
            "Which CRM platforms offer the best features for customer management?"
        ));

        // Project Management Tools prompts
        CATEGORY_PROMPTS.put("Project Management Tools", List.of(
            "What are the best project management tools for teams?",
            "Compare top project management software platforms",
            "What project management tools are recommended for remote teams?",
            "List the best project management software for agile teams",
            "Which project management platforms offer the best collaboration features?"
        ));

        // Email Marketing Platforms prompts
        CATEGORY_PROMPTS.put("Email Marketing Platforms", List.of(
            "What are the best email marketing platforms for businesses?",
            "Compare top email marketing software solutions",
            "What email marketing tools are recommended for e-commerce?",
            "List the best email marketing platforms for small businesses",
            "Which email marketing software offers the best automation features?"
        ));

        // E-commerce Platforms prompts
        CATEGORY_PROMPTS.put("E-commerce Platforms", List.of(
            "What are the best e-commerce platforms for online stores?",
            "Compare top e-commerce software solutions",
            "What e-commerce platforms are recommended for small businesses?",
            "List the best e-commerce tools for selling online",
            "Which e-commerce platforms offer the best features for online retail?"
        ));

        // Analytics Tools prompts
        CATEGORY_PROMPTS.put("Analytics Tools", List.of(
            "What are the best analytics tools for businesses?",
            "Compare top analytics software platforms",
            "What analytics tools are recommended for data analysis?",
            "List the best business analytics software solutions",
            "Which analytics platforms offer the best data visualization features?"
        ));

        // Customer Support Software prompts
        CATEGORY_PROMPTS.put("Customer Support Software", List.of(
            "What are the best customer support software solutions?",
            "Compare top customer service platforms",
            "What customer support tools are recommended for businesses?",
            "List the best helpdesk software for customer service teams",
            "Which customer support platforms offer the best ticketing features?"
        ));

        // Marketing Automation Tools prompts
        CATEGORY_PROMPTS.put("Marketing Automation Tools", List.of(
            "What are the best marketing automation platforms?",
            "Compare top marketing automation software solutions",
            "What marketing automation tools are recommended for businesses?",
            "List the best marketing automation platforms for lead generation",
            "Which marketing automation software offers the best campaign management features?"
        ));

        // Content Management Systems prompts
        CATEGORY_PROMPTS.put("Content Management Systems", List.of(
            "What are the best content management systems (CMS)?",
            "Compare top CMS platforms for websites",
            "What CMS platforms are recommended for content creators?",
            "List the best content management systems for blogs",
            "Which CMS platforms offer the best content editing features?"
        ));

        // Social Media Management prompts
        CATEGORY_PROMPTS.put("Social Media Management", List.of(
            "What are the best social media management tools?",
            "Compare top social media management platforms",
            "What social media tools are recommended for businesses?",
            "List the best social media management software for marketing teams",
            "Which social media platforms offer the best scheduling features?"
        ));

        // SEO Tools prompts
        CATEGORY_PROMPTS.put("SEO Tools", List.of(
            "What are the best SEO tools for website optimization?",
            "Compare top SEO software platforms",
            "What SEO tools are recommended for digital marketing?",
            "List the best SEO software for keyword research",
            "Which SEO platforms offer the best ranking tracking features?"
        ));

        // Design Tools prompts
        CATEGORY_PROMPTS.put("Design Tools", List.of(
            "What are the best design tools for graphic designers?",
            "Compare top design software platforms",
            "What design tools are recommended for creative professionals?",
            "List the best design software for UI/UX design",
            "Which design platforms offer the best collaboration features?"
        ));

        // Video Conferencing Tools prompts
        CATEGORY_PROMPTS.put("Video Conferencing Tools", List.of(
            "What are the best video conferencing tools for remote teams?",
            "Compare top video conferencing platforms",
            "What video conferencing software is recommended for businesses?",
            "List the best video conferencing tools for online meetings",
            "Which video conferencing platforms offer the best features for collaboration?"
        ));
    }

    /**
     * Get prompts for a specific category
     * @param displayName Category display name
     * @return List of prompts for the category, or default prompts if not found
     */
    public static List<String> getPrompts(String displayName) {
        List<String> prompts = CATEGORY_PROMPTS.get(displayName);
        if (prompts == null || prompts.isEmpty()) {
            // Fallback to default prompts
            return getDefaultPrompts(displayName);
        }
        return new ArrayList<>(prompts);
    }

    /**
     * Default prompts if category-specific prompts are not found
     */
    private static List<String> getDefaultPrompts(String category) {
        List<String> defaultPrompts = new ArrayList<>();
        defaultPrompts.add("What are the best " + category.toLowerCase() + " for businesses?");
        defaultPrompts.add("Compare top " + category.toLowerCase() + " platforms");
        defaultPrompts.add("What " + category.toLowerCase() + " are recommended?");
        defaultPrompts.add("List the best " + category.toLowerCase() + " solutions");
        defaultPrompts.add("Which " + category.toLowerCase() + " offer the best features?");
        return defaultPrompts;
    }
}

