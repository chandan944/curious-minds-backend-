package com.DSA.auth;

import lombok.Data;

@Data
public class GoogleAuthRequest {
    private String idToken; // Contains Google access token from Expo
    private String email;
    private String name;
    private String imageUrl;
    
    // Gamification fields
    private int streak;
    private int level;
    private int points;
    private String title;
}
