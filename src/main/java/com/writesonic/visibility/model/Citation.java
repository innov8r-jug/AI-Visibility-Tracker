package com.writesonic.visibility.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "citations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Citation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "mention_id", nullable = false)
    private Mention mention;
    
    @Column(columnDefinition = "TEXT")
    private String sourceUrl;
    
    private String sourceTitle;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AIModel aiModel;
}

