package com.writesonic.visibility.controller;

import com.writesonic.visibility.dto.CustomPromptRequest;
import com.writesonic.visibility.dto.CustomAnalysisResult;
import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.service.DynamicVisibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/visibility")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CustomPromptController {

    private final DynamicVisibilityService visibilityService;

    @PostMapping("/analyze")
    public ResponseEntity<CustomAnalysisResult> analyzePrompt(@RequestBody CustomPromptRequest request) {
        List<AIModel> selectedModels = null;

        if (request.getAiModels() != null && !request.getAiModels().isEmpty()) {
            selectedModels = request.getAiModels().stream()
                    .map(AIModel::fromCode)
                    .collect(Collectors.toList());
        }

        CustomAnalysisResult result = visibilityService.analyzeCustomPrompt(
                request.getUserPrompt(),
                request.getTargetBrands(),
                selectedModels
        );

        return ResponseEntity.ok(result);
    }
}