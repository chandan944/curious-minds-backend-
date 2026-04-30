package com.DSA.controller;

import com.DSA.auth.GoogleAuthRequest;
import com.DSA.auth.JwtService;
import com.DSA.user.Role;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import io.jsonwebtoken.Claims;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final com.DSA.user.PointTransactionRepository pointTransactionRepository;

    @Value("${google.client.id}")
    private String googleClientId;

    // Define admin emails here (or move to application.properties)
    private static final List<String> ADMIN_EMAILS = Arrays.asList(
            "kanchanparajapati4@gmail.com"
    );

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString(),
                "service", "DSA Backend",
                "version", "1.0.0"
        ));
    }

    // ── Validate JWT + verify user exists in DB ───────────────────────────────
    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "message", "Missing or invalid token"));
            }
            String token = authHeader.substring(7);
            Claims claims = jwtService.extractClaims(token);
            String email = claims.get("email", String.class);
            Long userId = claims.get("userId", Long.class);

            if (email == null || userId == null) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "message", "Malformed token claims"));
            }

            // Verify user actually exists in DB
            return userRepository.findById(userId).map(u -> {
                // Double-check email matches (prevents ID reuse after DB wipe)
                if (!u.getEmail().equals(email)) {
                    return ResponseEntity.status(401).body(Map.of("valid", false, "message", "Token email mismatch"));
                }
                return ResponseEntity.ok(Map.of(
                        "valid", true,
                        "user", Map.of(
                                "id", u.getId(),
                                "email", u.getEmail(),
                                "name", u.getName(),
                                "imageUrl", u.getImageUrl() != null ? u.getImageUrl() : "",
                                "role", u.getRole().name(),
                                "streak", u.getStreak(),
                                "level", u.getLevel(),
                                "points", u.getPoints(),
                                "title", u.getTitle()
                        )
                ));
            }).orElseGet(() ->
                    ResponseEntity.status(404).body(Map.of("valid", false, "message", "User not found in database"))
            );

        } catch (Exception e) {
            System.err.println("⚠️ Token validation error: " + e.getMessage());
            return ResponseEntity.status(401).body(Map.of("valid", false, "message", "Token expired or invalid"));
        }
    }

    @PostMapping("/google")
    public ResponseEntity<?> authenticateWithGoogle(@RequestBody GoogleAuthRequest request) {
        try {
            System.out.println("📥 Received Google auth request");
            System.out.println("📧 Email: " + request.getEmail());
            System.out.println("👤 Name: " + request.getName());

            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    new GsonFactory()
            )
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(request.getIdToken());

            if (idToken == null) {
                System.err.println("❌ Invalid Google ID token");
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Invalid Google token"
                ));
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            String email = payload.getEmail();

            if (!email.equals(request.getEmail())) {
                System.err.println("❌ Email mismatch");
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Email verification failed"
                ));
            }

            System.out.println("✅ Google token verified for: " + email);

            Role userRole = isAdminEmail(email) ? Role.ADMIN : Role.USER;
            System.out.println("🔐 Assigned role: " + userRole + " for email: " + email);

            User user = userRepository.findByEmail(email)
                    .orElseGet(() -> {
                        System.out.println("🆕 Creating new user: " + email);
                        return userRepository.save(
                                User.builder()
                                        .email(email)
                                        .name(request.getName())
                                        .imageUrl(request.getImageUrl())
                                        .role(userRole)
                                        .streak(0)
                                        .level(1)
                                        .points(0)
                                        .title("Curious Kid")
                                        .build()
                        );
                    });

            boolean updated = false;
            
            // Sync user.points with actual transactions to fix any corruption/mismatches
            Integer truePoints = pointTransactionRepository.getTotalPointsForUser(user.getId());
            if (truePoints != null && truePoints != user.getPoints()) {
                System.out.println("🔄 Syncing user points from " + user.getPoints() + " to true value: " + truePoints);
                user.setPoints(truePoints);
                
                // Recalculate level & title
                String[] titles = {"Curious Kid", "Explorer", "Thinker", "Investigator", "Scientist", "Researcher", "Innovator", "Genius", "Mastermind", "Einstein Mode"};
                int[] thresholds = {0, 300, 800, 1800, 4000, 8000, 15000, 28000, 50000, 100000};
                int newLevel = 1;
                String newTitle = titles[0];
                for (int i = thresholds.length - 1; i >= 0; i--) {
                    if (user.getPoints() >= thresholds[i]) {
                        newLevel = i + 1;
                        newTitle = titles[i];
                        break;
                    }
                }
                user.setLevel(newLevel);
                user.setTitle(newTitle);
                updated = true;
            }

            if (!user.getRole().equals(userRole)) {
                System.out.println("🔄 Updating user role from " + user.getRole() + " to " + userRole);
                user.setRole(userRole);
                updated = true;
            }
            
            if (updated) {
                userRepository.save(user);
            }

            String jwtToken = jwtService.generateToken(user);
            System.out.println("🎫 JWT generated for user ID: " + user.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "token", jwtToken,
                    "user", Map.of(
                            "id", user.getId(),
                            "email", user.getEmail(),
                            "name", user.getName(),
                            "imageUrl", user.getImageUrl() != null ? user.getImageUrl() : "",
                            "role", user.getRole().name(),
                            "streak", user.getStreak(),
                            "level", user.getLevel(),
                            "points", user.getPoints(),
                            "title", user.getTitle()
                    )
            ));

        } catch (Exception e) {
            System.err.println("❌ Authentication error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Authentication failed: " + e.getMessage()
            ));
        }
    }

    private boolean isAdminEmail(String email) {
        return ADMIN_EMAILS.stream()
                .anyMatch(adminEmail -> adminEmail.equalsIgnoreCase(email));
    }
}
