package com.writesonic.visibility.dto;

import com.writesonic.visibility.model.AIModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ObjectUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VisibilityRequest {
    private String category;
    private List<String> brands;
    private List<String> aiModels; // Accept as strings from frontend (e.g., "Gemini", "Groq")

    /**
     * Convert string model codes to AIModel enum list
     * Supports: "Gemini" -> GEMINI, "Groq" -> GROQ
     */
    public List<AIModel> getAiModelsAsEnum() {
        if (ObjectUtils.isEmpty(aiModels)) {
            log.warn("No AI models provided in request");
            return null;
        }

        return aiModels.stream()
                .map(code -> {
                    AIModel model = AIModel.fromCode(code);
                    if (model == null) {
                        log.error("Invalid AI model code received: {}", code);
                        throw new IllegalArgumentException("Invalid AI model: " + code + ". Supported models: Gemini, Groq");
                    }
                    log.debug("Mapped AI model code '{}' to enum {}", code, model);
                    return model;
                })
                .collect(Collectors.toList());
    }
}

