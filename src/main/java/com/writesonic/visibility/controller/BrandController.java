package com.writesonic.visibility.controller;

import com.writesonic.visibility.model.Brand;
import com.writesonic.visibility.model.Category;
import com.writesonic.visibility.repository.BrandRepository;
import com.writesonic.visibility.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BrandController {
    
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    
    @GetMapping
    public ResponseEntity<List<Brand>> getAllBrands() {
        return ResponseEntity.ok(brandRepository.findAll());
    }
    
    @GetMapping("/category/{categoryName}")
    public ResponseEntity<List<Brand>> getBrandsByCategory(@PathVariable String categoryName) {
        Category category = categoryRepository.findByName(categoryName)
                .orElse(null);
        if (category == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(brandRepository.findByCategory(category));
    }
}

