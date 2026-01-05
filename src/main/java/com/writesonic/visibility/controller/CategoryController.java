package com.writesonic.visibility.controller;

import com.writesonic.visibility.util.CategoryUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CategoryController {
    
    @GetMapping
    public ResponseEntity<String[]> getAllCategories() {
        return ResponseEntity.ok(CategoryUtils.getAvailableCategories());
    }
}

