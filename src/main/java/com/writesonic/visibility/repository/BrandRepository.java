package com.writesonic.visibility.repository;

import com.writesonic.visibility.model.Brand;
import com.writesonic.visibility.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandRepository extends JpaRepository<Brand, Long> {
    Optional<Brand> findByName(String name);
    List<Brand> findByCategory(Category category);
    List<Brand> findByNameIn(List<String> names);
}

