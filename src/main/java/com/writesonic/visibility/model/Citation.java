package com.writesonic.visibility.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "citations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Citation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mention_id", nullable = false)
    private Mention mention;

    @Column(columnDefinition = "TEXT")
    private String sourceUrl;

    private String sourceTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AIModel aiModel;
}