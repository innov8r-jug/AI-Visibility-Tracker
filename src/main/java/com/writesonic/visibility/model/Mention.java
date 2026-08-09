package com.writesonic.visibility.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "mentions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Mention {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prompt_id", nullable = false)
    private Prompt prompt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AIModel aiModel;

    @Column(columnDefinition = "TEXT")
    private String context;

    private Integer position;
    private String sentiment;

    @OneToMany(mappedBy = "mention", cascade = CascadeType.ALL)
    private List<Citation> citations = new ArrayList<>();
}