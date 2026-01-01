package com.writesonic.visibility.repository;

import com.writesonic.visibility.model.AIModel;
import com.writesonic.visibility.model.Citation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CitationRepository extends JpaRepository<Citation, Long> {
    List<Citation> findByAiModel(AIModel aiModel);
    
    @Query("SELECT c.sourceUrl, c.sourceTitle, COUNT(c) as count FROM Citation c WHERE c.sourceUrl IS NOT NULL GROUP BY c.sourceUrl, c.sourceTitle ORDER BY count DESC")
    List<Object[]> findTopCitedPages();
    
    @Query("SELECT c.sourceUrl, c.sourceTitle, COUNT(c) as count FROM Citation c WHERE c.aiModel = :aiModel AND c.sourceUrl IS NOT NULL GROUP BY c.sourceUrl, c.sourceTitle ORDER BY count DESC")
    List<Object[]> findTopCitedPagesByModel(@Param("aiModel") AIModel aiModel);
}

