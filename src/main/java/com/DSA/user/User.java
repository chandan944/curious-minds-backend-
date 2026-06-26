package com.DSA.user;

import lombok.*;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    private Long id;
    private String name;
    private String email;
    private String imageUrl;
    private String bio;
    private Role role;

    // Gamification fields
    @Builder.Default
    private int streak = 0;

    @Builder.Default
    private int level = 1;

    @Builder.Default
    private int points = 0;

    @Builder.Default
    private String title = "Curious Kid";

    @Builder.Default
    private boolean isPrivateProfile = false;

    private Instant lastPointsEarnedAt;
    private String expoPushToken;

    @Builder.Default
    private boolean isPremium = false;

    public String getIdString() {
        return id != null ? String.valueOf(id) : null;
    }
}
