package com.writesonic.visibility;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class VisibilityTrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(VisibilityTrackerApplication.class, args);
    }
}

