package com.writesonic.visibility.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * AsyncConfig - Multi-threaded AI query processing
 * 
 * This configuration enables parallel processing of AI queries for better performance.
 * Multiple AI services can be queried simultaneously instead of sequentially.
 * 
 * Thread Pool Configuration:
 * - Core Pool Size: 5 threads (always available)
 * - Max Pool Size: 10 threads (can expand under load)
 * - Queue Capacity: 100 tasks (buffer for pending requests)
 * 
 * Debug: Thread names prefixed with "ai-query-" for easy identification in logs
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean(name = "aiQueryExecutor")
    public Executor aiQueryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);        // Minimum threads always running
        executor.setMaxPoolSize(10);        // Maximum threads when busy
        executor.setQueueCapacity(100);      // Queue size before rejecting tasks
        executor.setThreadNamePrefix("ai-query-");  // For debugging: identify threads in logs
        executor.setWaitForTasksToCompleteOnShutdown(true);  // Wait for tasks on shutdown
        executor.setAwaitTerminationSeconds(60);  // Wait up to 60s for tasks to complete
        executor.initialize();
        return executor;
    }
}

