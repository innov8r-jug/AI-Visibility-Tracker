package com.writesonic.visibility.service.util;

import com.writesonic.visibility.model.Citation;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared regex-based URL/citation extraction from free-form LLM response text. Used by
 * every AIService implementation so this logic lives in exactly one place instead of
 * being copy-pasted per provider.
 */
public final class CitationTextExtractor {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b(https?://[^\\s<>\"'{}|\\\\^`\\[\\]]+)|(www\\.[^\\s<>\"'{}|\\\\^`\\[\\]]+)");

    private CitationTextExtractor() {
    }

    public static List<Citation> extract(String response) {
        List<Citation> citations = new ArrayList<>();
        if (response == null || response.isEmpty()) {
            return citations;
        }

        Matcher matcher = URL_PATTERN.matcher(response);
        Set<String> foundUrls = new HashSet<>();

        while (matcher.find()) {
            String url = matcher.group(0).replaceAll("[.,;:!?]+$", "");
            if (url.startsWith("www.")) {
                url = "https://" + url;
            }
            if (url.length() < 10 || !foundUrls.add(url)) {
                continue;
            }
            try {
                new URL(url);
            } catch (MalformedURLException e) {
                continue;
            }

            String title = extractTitleFromMarkdown(response, url);
            if (title == null || title.isEmpty()) {
                title = extractDomainName(url);
            }

            Citation citation = new Citation();
            citation.setSourceUrl(url);
            citation.setSourceTitle(title);
            citations.add(citation);
        }
        return citations;
    }

    private static String extractTitleFromMarkdown(String response, String url) {
        String markdownPattern = "\\[([^\\]]+)\\]\\(" + Pattern.quote(url) + "\\)";
        Matcher matcher = Pattern.compile(markdownPattern, Pattern.CASE_INSENSITIVE).matcher(response);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String extractDomainName(String url) {
        try {
            String host = new URL(url).getHost();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return url.length() > 50 ? url.substring(0, 50) + "..." : url;
        }
    }
}
