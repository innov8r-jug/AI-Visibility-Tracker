package com.writesonic.visibility.repository;

import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Category;
import com.writesonic.visibility.model.Prompt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PromptRepository extends JpaRepository<Prompt, Long> {

    // Using JOIN FETCH to prevent N+1 Database queries
    @Query("SELECT DISTINCT p FROM Prompt p LEFT JOIN FETCH p.mentions m LEFT JOIN FETCH m.brand WHERE p.category = :category")
    List<Prompt> findByCategoryWithMentions(@Param("category") Category category);

    List<Prompt> findByAiModel(AIModel aiModel);
}