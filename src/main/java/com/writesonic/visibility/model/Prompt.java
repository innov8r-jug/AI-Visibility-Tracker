package com.writesonic.visibility.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "prompts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Prompt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String queryText;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AIModel aiModel;
    
    @Column(columnDefinition = "TEXT")
    private String response;
    
    @Column(nullable = false)
    private LocalDateTime timestamp;
    
    @OneToMany(mappedBy = "prompt", cascade = CascadeType.ALL)
    private List<Mention> mentions = new ArrayList<>();
    
    @PrePersist
    protected void onCreate() {
        timestamp = LocalDateTime.now();
    }
}

