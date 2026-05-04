package com.DSA.user;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_points", columnList = "points DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    private String imageUrl;

    @Enumerated(EnumType.STRING)
    private Role role;

    // Gamification fields
    @Column(nullable = false, columnDefinition = "int default 0")
    private int streak;

    @Column(nullable = false, columnDefinition = "int default 1")
    private int level;

    @Column(nullable = false, columnDefinition = "int default 0")
    private int points;

    @Column(nullable = false, columnDefinition = "varchar(255) default 'Curious Kid'")
    private String title;

    // Tracks when the user last earned any XP (used for hour/day/week leaderboard windows)
    @Column
    private LocalDateTime lastPointsEarnedAt;
}
