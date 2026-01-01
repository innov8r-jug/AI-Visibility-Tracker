package com.writesonic.visibility.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for category name conversion between display format and camelCase storage format
 */
public class CategoryUtils {

    // Map of display name -> camelCase storage name
    private static final Map<String, String> DISPLAY_TO_CAMEL = new HashMap<>();

    // Map of camelCase storage name -> display name
    private static final Map<String, String> CAMEL_TO_DISPLAY = new HashMap<>();

    static {
        // Define all available categories
        addCategory("CRM Software", "crmSoftware");
        addCategory("Project Management Tools", "projectManagementTools");
        addCategory("Email Marketing Platforms", "emailMarketingPlatforms");
        addCategory("E-commerce Platforms", "ecommercePlatforms");
        addCategory("Analytics Tools", "analyticsTools");
        addCategory("Customer Support Software", "customerSupportSoftware");
        addCategory("Marketing Automation Tools", "marketingAutomationTools");
        addCategory("Content Management Systems", "contentManagementSystems");
        addCategory("Social Media Management", "socialMediaManagement");
        addCategory("SEO Tools", "seoTools");
        addCategory("Design Tools", "designTools");
        addCategory("Video Conferencing Tools", "videoConferencingTools");
    }

    private static void addCategory(String displayName, String camelCase) {
        DISPLAY_TO_CAMEL.put(displayName, camelCase);
        CAMEL_TO_DISPLAY.put(camelCase, displayName);
    }

    /**
     * Convert display name to camelCase for database storage
     * @param displayName Display name (e.g., "CRM Software")
     * @return CamelCase name (e.g., "crmSoftware") or null if not found
     */
    public static String toCamelCase(String displayName) {
        return DISPLAY_TO_CAMEL.get(displayName);
    }

    /**
     * Convert camelCase storage name to display format
     * @param camelCase CamelCase name (e.g., "crmSoftware")
     * @return Display name (e.g., "CRM Software") or null if not found
     */
    public static String toDisplayName(String camelCase) {
        return CAMEL_TO_DISPLAY.get(camelCase);
    }

    /**
     * Get all available category display names
     * @return Array of display names
     */
    public static String[] getAvailableCategories() {
        return DISPLAY_TO_CAMEL.keySet().toArray(new String[0]);
    }

    /**
     * Check if a category is valid (by display name)
     * @param displayName Display name to check
     * @return true if valid, false otherwise
     */
    public static boolean isValidCategory(String displayName) {
        return DISPLAY_TO_CAMEL.containsKey(displayName);
    }

    /**
     * Get all available category camelCase keys
     * @return Set of camelCase keys
     */
    public static Set<String> getCategoryKeys() {
        return DISPLAY_TO_CAMEL.values().stream().collect(Collectors.toSet());
    }
}

