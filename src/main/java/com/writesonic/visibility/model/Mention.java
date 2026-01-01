package com.writesonic.visibility.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "mentions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Mention {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "prompt_id", nullable = false)
    private Prompt prompt;
    
    @ManyToOne
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AIModel aiModel;
    
    @Column(columnDefinition = "TEXT")
    private String context;
    
    private Integer position;
    
    private String sentiment; // POSITIVE, NEGATIVE, NEUTRAL
    
    @OneToMany(mappedBy = "mention", cascade = CascadeType.ALL)
    private List<Citation> citations = new ArrayList<>();
}

