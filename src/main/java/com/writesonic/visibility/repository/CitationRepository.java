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
    
    /**
     * Get top cited pages filtered by AI model
     * Used by AnalysisService to populate "Top Cited Pages" section in dashboard
     * Returns: List of [sourceUrl, sourceTitle, count] arrays, sorted by citation count (descending)
     * 
     * Frontend Usage:
     * - "General" tab: Aggregates results from all models
     * - "Platforms" tab: Shows per-model top cited pages
     */
    @Query("SELECT c.sourceUrl, c.sourceTitle, COUNT(c) as count " +
           "FROM Citation c WHERE c.aiModel = :aiModel AND c.sourceUrl IS NOT NULL " +
           "GROUP BY c.sourceUrl, c.sourceTitle ORDER BY count DESC")
    List<Object[]> findTopCitedPagesByModel(@Param("aiModel") AIModel aiModel);
    
    /**
     * Count total citations for a specific category
     * Used by AnalysisService to calculate "Total Pages Cited" metric
     * 
     * Frontend Usage:
     * - Displays in "Total Pages Cited" card in MetricsCard component
     */
    @Query("SELECT COUNT(c) FROM Citation c WHERE c.mention.brand.category.id = :categoryId")
    Long countByCategoryId(@Param("categoryId") Long categoryId);
}

