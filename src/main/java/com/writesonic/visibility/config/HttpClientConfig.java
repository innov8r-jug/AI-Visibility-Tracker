package com.writesonic.visibility.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {
    
    /**
     * Configure OkHttpClient with appropriate timeouts
     * Reduced timeouts to prevent hanging:
     * - Connect timeout: 30s (time to establish connection)
     * - Read timeout: 30s (time to read response) - reduced from 60s
     * - Write timeout: 30s (time to send request) - reduced from 60s
     * 
     * If API calls take longer than 30s, they will timeout and fail fast
     * rather than hanging for 60 seconds.
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)  // Time to establish connection
                .readTimeout(30, TimeUnit.SECONDS)     // Time to read response (reduced from 60s)
                .writeTimeout(30, TimeUnit.SECONDS)     // Time to send request (reduced from 60s)
                .retryOnConnectionFailure(true)         // Retry on connection failures
                .build();
    }
}

