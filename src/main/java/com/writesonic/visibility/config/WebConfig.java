package com.writesonic.visibility.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Single source of truth for CORS. Controllers must NOT carry their own
 * @CrossOrigin annotations - a duplicated/contradictory config (this filter
 * restricting to a pattern while a controller says "*") is confusing and only
 * one of them actually wins at runtime.
 */
@Configuration
public class WebConfig {

    @Value("${cors.allowed-origin-pattern:http://localhost:*}")
    private String allowedOriginPattern;

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern(allowedOriginPattern);
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}

