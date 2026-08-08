package com.writesonic.visibility.controller;

import com.writesonic.visibility.model.Category;
import com.writesonic.visibility.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CategoryController {

    private final CategoryRepository categoryRepository;

    @GetMapping
    public ResponseEntity<List<String>> getAllCategories() {
        // Fetch all dynamic categories straight from the database
        List<String> categories = categoryRepository.findAll().stream()
                .map(Category::getName)
                .collect(Collectors.toList());

        return ResponseEntity.ok(categories);
    }
}