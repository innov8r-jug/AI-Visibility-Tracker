package com.writesonic.visibility.service;

import com.writesonic.visibility.model.AIModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AIServiceFactory {
    
    private final List<AIService> aiServices;
    
    /**
     * Get all available AI services
     */
    public List<AIService> getAllServices() {
        return aiServices.stream()
                .filter(AIService::isAvailable)
                .collect(Collectors.toList());
    }
    
    /**
     * Get services by model types
     */
    public List<AIService> getServicesByModels(List<AIModel> models) {
        return aiServices.stream()
                .filter(service -> models.contains(service.getModelType()))
                .filter(AIService::isAvailable)
                .collect(Collectors.toList());
    }
    
    /**
     * Get a specific service by model type
     */
    public AIService getServiceByModel(AIModel model) {
        return aiServices.stream()
                .filter(service -> service.getModelType() == model)
                .filter(AIService::isAvailable)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Service not available for model: " + model));
    }
    
    /**
     * Get all available model types
     */
    public List<AIModel> getAvailableModels() {
        return aiServices.stream()
                .filter(AIService::isAvailable)
                .map(AIService::getModelType)
                .collect(Collectors.toList());
    }
}

