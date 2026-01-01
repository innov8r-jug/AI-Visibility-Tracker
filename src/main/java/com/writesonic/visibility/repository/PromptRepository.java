package com.writesonic.visibility.repository;

import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Category;
import com.writesonic.visibility.model.Prompt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PromptRepository extends JpaRepository<Prompt, Long> {
    List<Prompt> findByCategory(Category category);
    List<Prompt> findByAiModel(AIModel aiModel);
    List<Prompt> findByCategoryAndAiModel(Category category, AIModel aiModel);
}

