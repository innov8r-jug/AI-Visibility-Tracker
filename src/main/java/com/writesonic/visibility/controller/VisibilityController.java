package com.writesonic.visibility.controller;

import com.writesonic.visibility.dto.VisibilityRequest;
import com.writesonic.visibility.dto.VisibilityResponse;
import com.writesonic.visibility.service.AnalysisService;
import com.writesonic.visibility.service.VisibilityService;
import com.writesonic.visibility.service.dto.DashboardData;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/visibility")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class VisibilityController {
    
    private final VisibilityService visibilityService;
    private final AnalysisService analysisService;
    
    @PostMapping("/analyze")
    public ResponseEntity<VisibilityResponse> analyzeVisibility(@RequestBody VisibilityRequest request) {
        try {
            new Thread(() -> {
                try {
                    visibilityService.analyzeVisibility(
                            request.getCategory(),
                            request.getBrands(),
                            request.getAiModels()
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
            
            String analysisId = UUID.randomUUID().toString();
            return ResponseEntity.ok(VisibilityResponse.builder()
                    .analysisId(analysisId)
                    .status("processing")
                    .message("Analysis started")
                    .build());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(VisibilityResponse.builder()
                            .status("error")
                            .message(e.getMessage())
                            .build());
        }
    }
    
    @GetMapping("/dashboard/{category}")
    public ResponseEntity<DashboardData> getDashboard(@PathVariable String category) {
        try {
            DashboardData data = analysisService.getDashboardData(category);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}

