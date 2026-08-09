package com.writesonic.visibility;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class VisibilityTrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(VisibilityTrackerApplication.class, args);
    }
}