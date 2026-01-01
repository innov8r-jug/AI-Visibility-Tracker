package com.writesonic.visibility.repository;

import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Brand;
import com.writesonic.visibility.model.Mention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MentionRepository extends JpaRepository<Mention, Long> {
    List<Mention> findByBrand(Brand brand);
    List<Mention> findByAiModel(AIModel aiModel);
    List<Mention> findByBrandAndAiModel(Brand brand, AIModel aiModel);
    
    @Query("SELECT COUNT(m) FROM Mention m WHERE m.brand = :brand")
    Long countByBrand(@Param("brand") Brand brand);
    
    @Query("SELECT COUNT(m) FROM Mention m WHERE m.brand = :brand AND m.aiModel = :aiModel")
    Long countByBrandAndAiModel(@Param("brand") Brand brand, @Param("aiModel") AIModel aiModel);
}

