package com.writesonic.visibility.controller;

import com.writesonic.visibility.dto.VisibilityRequest;
import com.writesonic.visibility.dto.VisibilityResponse;
import com.writesonic.visibility.service.AIService;
import com.writesonic.visibility.service.AIServiceFactory;
import com.writesonic.visibility.service.AnalysisService;
import com.writesonic.visibility.service.VisibilityService;
import com.writesonic.visibility.service.dto.DashboardData;
import com.writesonic.visibility.util.CategoryUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/visibility")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class VisibilityController {

    private final VisibilityService visibilityService;
    private final AnalysisService analysisService;
    private final AIServiceFactory aiServiceFactory;

    @PostMapping("/analyze")
    public ResponseEntity<VisibilityResponse> analyzeVisibility(@RequestBody VisibilityRequest request) {
        log.info("Received visibility analysis request - Category: {}, Brands: {}, AI Models: {}",
                request.getCategory(), request.getBrands(), request.getAiModels());

        try {
            // Validate request
            if (ObjectUtils.isEmpty(request.getCategory())) {
                log.warn("Analysis request rejected: Category is required");
                return ResponseEntity.badRequest()
                        .body(VisibilityResponse.builder()
                                .status("error")
                                .message("Category is required")
                                .build());
            }

            // Validate category is in the allowed list
            if (!CategoryUtils.isValidCategory(request.getCategory())) {
                log.warn("Analysis request rejected: Invalid category: {}", request.getCategory());
                return ResponseEntity.badRequest()
                        .body(VisibilityResponse.builder()
                                .status("error")
                                .message("Invalid category. Please select from available options.")
                                .build());
            }

            if (ObjectUtils.isEmpty(request.getBrands())) {
                log.warn("Analysis request rejected: At least one brand is required");
                return ResponseEntity.badRequest()
                        .body(VisibilityResponse.builder()
                                .status("error")
                                .message("At least one brand is required")
                                .build());
            }

            if (ObjectUtils.isEmpty(request.getAiModels())) {
                log.warn("Analysis request rejected: AI Model is required");
                return ResponseEntity.badRequest()
                        .body(VisibilityResponse.builder()
                                .status("error")
                                .message("AI Model is required")
                                .build());
            }

            String analysisId = UUID.randomUUID().toString();
            log.info("Starting analysis with ID: {} for category: {}", analysisId, request.getCategory());

            new Thread(() -> {
                try {
                    log.debug("Executing analysis in background thread for category: {}", request.getCategory());
                    visibilityService.analyzeVisibility(
                            request.getCategory(),
                            request.getBrands(),
                            request.getAiModelsAsEnum()
                    );
                    log.info("Analysis completed successfully for category: {}", request.getCategory());
                } catch (Exception e) {
                    log.error("Error during analysis execution for category: {}", request.getCategory(), e);
                }
            }).start();

            return ResponseEntity.ok(VisibilityResponse.builder()
                    .analysisId(analysisId)
                    .status("processing")
                    .message("Analysis started")
                    .build());
        } catch (Exception e) {
            log.error("Error processing analysis request", e);
            return ResponseEntity.badRequest()
                    .body(VisibilityResponse.builder()
                            .status("error")
                            .message(e.getMessage())
                            .build());
        }
    }

    @GetMapping("/dashboard/{category}")
    public ResponseEntity<DashboardData> getDashboard(@PathVariable String category) {
        log.info("Fetching dashboard data for category: {}", category);
        try {
            DashboardData data = analysisService.getDashboardData(category);
            log.debug("Dashboard data retrieved successfully for category: {}", category);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error fetching dashboard data for category: {}", category, e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Test endpoint to verify prompts and services are working
     */
    @GetMapping("/test/prompts/{category}")
    public ResponseEntity<Map<String, Object>> testPrompts(@PathVariable String category) {
        log.info("Testing prompts for category: {}", category);
        Map<String, Object> result = new HashMap<>();

        try {
            // Check if category is valid
            boolean isValid = CategoryUtils.isValidCategory(category);
            result.put("categoryValid", isValid);
            result.put("category", category);

            if (isValid) {
                // Get prompts
                List<String> prompts = visibilityService.generatePrompts(category);
                result.put("prompts", prompts);
                result.put("promptCount", prompts.size());

                // Check available services
                List<AIService> services = aiServiceFactory.getAllServices();
                result.put("availableServices", services.stream()
                        .map(service -> Map.of(
                                "name", service.getModelName(),
                                "type", service.getModelType().getCode(),
                                "available", service.isAvailable()
                        ))
                        .collect(Collectors.toList()));
                result.put("serviceCount", services.size());

                result.put("status", "success");
            } else {
                result.put("status", "error");
                result.put("message", "Invalid category. Use GET /api/categories to see valid options.");
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error testing prompts for category: {}", category, e);
            result.put("status", "error");
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}

