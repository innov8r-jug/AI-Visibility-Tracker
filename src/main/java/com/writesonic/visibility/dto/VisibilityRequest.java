package com.writesonic.visibility.dto;

import com.writesonic.visibility.model.AIModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VisibilityRequest {
    private String category;
    private List<String> brands;
    private List<AIModel> aiModels;
}

