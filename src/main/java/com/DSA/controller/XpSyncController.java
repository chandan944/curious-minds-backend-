package com.DSA.controller;

import com.DSA.auth.JwtService;
import com.DSA.user.PointTransaction;
import com.DSA.user.PointTransactionRepository;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;


@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class XpSyncController {

    private final UserRepository userRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final JwtService jwtService;

    // ── Allowed XP reasons + max points per action ─────────────────────────────
    private static final Map<String, Integer> ALLOWED_REASONS = Map.of(
            "quiz_correct",    15,
            "quiz_perfect",    50,
            "theory_read",     10,
            "lab_complete",    20,
            "dyk_complete",    10,
            "streak_bonus",    25,
            "topic_complete",  200,
            "local_earn",      15   // Generic local earn
    );

    // Max XP that can be synced in a single request (safety cap)
    private static final int MAX_SINGLE_SYNC = 500;

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /user/sync-xp
    //  Body: { points: 15, reason: "quiz_correct", streak: 3 }
    //  Header: Authorization: Bearer <jwt>
    // ──────────────────────────────────────────────────────────────────────────
    @PostMapping("/sync-xp")
    public ResponseEntity<?> syncXp(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, Object> body) {
        try {
            // Extract JWT
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Missing token"));
            }
            String token = authHeader.substring(7);
            Claims claims = jwtService.extractClaims(token);
            Object userIdObj = claims.get("userId");
            Long userId = null;
            if (userIdObj instanceof Number) {
                userId = ((Number) userIdObj).longValue();
            }

            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Invalid token: no userId"));
            }

            Optional<User> optUser = userRepository.findById(userId);
            if (optUser.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found"));
            }

            User user = optUser.get();

            // ── Validate points ────────────────────────────────────────────
            Object pointsObj = body.get("points");
            if (pointsObj == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Missing 'points' field"));
            }
            int pointsToAdd;
            try {
                pointsToAdd = ((Number) pointsObj).intValue();
            } catch (ClassCastException e) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid 'points' value"));
            }

            if (pointsToAdd <= 0) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Points must be > 0"));
            }

            // ── Validate reason ────────────────────────────────────────────
            String reason = (String) body.getOrDefault("reason", "unknown");

            // Check reason is allowed
            Integer maxForReason = ALLOWED_REASONS.get(reason);
            if (maxForReason == null) {
                // Unknown reason — cap at minimum
                maxForReason = 15;
                System.out.println("⚠️ Unknown XP reason: " + reason + " from user " + userId);
            }

            // Cap points to the maximum allowed for this reason
            if (pointsToAdd > maxForReason) {
                System.out.println("⚠️ XP cap enforced: requested=" + pointsToAdd + " max=" + maxForReason + " reason=" + reason + " user=" + userId);
                pointsToAdd = maxForReason;
            }

            // Global safety cap
            if (pointsToAdd > MAX_SINGLE_SYNC) {
                System.out.println("⚠️ Global XP cap enforced: " + pointsToAdd + " → " + MAX_SINGLE_SYNC + " for user " + userId);
                pointsToAdd = MAX_SINGLE_SYNC;
            }

            // ── Validate streak ────────────────────────────────────────────
            int newStreak = user.getStreak();
            Object streakObj = body.get("streak");
            if (streakObj != null) {
                try {
                    int clientStreak = ((Number) streakObj).intValue();
                    // Only allow streak to increase by 1 at a time (prevents fabrication)
                    if (clientStreak >= 0 && clientStreak <= user.getStreak() + 1) {
                        newStreak = Math.max(user.getStreak(), clientStreak);
                    } else {
                        System.out.println("⚠️ Suspicious streak value: " + clientStreak + " (current: " + user.getStreak() + ") for user " + userId);
                    }
                } catch (ClassCastException ignored) {}
            }

            // ── Apply points ────────────────────────────────────────────────
            int newTotal = user.getPoints() + pointsToAdd;
            user.setPoints(newTotal);
            user.setStreak(newStreak);
            user.setLastPointsEarnedAt(LocalDateTime.now());

            // Recalculate level & title server-side
            String[] titles = {"Curious Kid", "Explorer", "Thinker", "Investigator",
                    "Scientist", "Researcher", "Innovator", "Genius", "Mastermind", "Einstein Mode"};
            int[] thresholds = {0, 300, 800, 1800, 4000, 8000, 15000, 28000, 50000, 100000};
            int newLevel = 1;
            String newTitle = titles[0];
            for (int i = thresholds.length - 1; i >= 0; i--) {
                if (newTotal >= thresholds[i]) {
                    newLevel = i + 1;
                    newTitle = titles[i];
                    break;
                }
            }
            user.setLevel(newLevel);
            user.setTitle(newTitle);
            userRepository.save(user);

            // Log the transaction
            pointTransactionRepository.save(
                    PointTransaction.builder()
                            .user(user)
                            .amount(pointsToAdd)
                            .earnedAt(LocalDateTime.now())
                            .reason(reason)
                            .build()
            );

            System.out.println("✅ XP synced for user " + userId + ": +" + pointsToAdd + " (" + reason + ") → total: " + newTotal);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "totalPoints", newTotal,
                    "level", newLevel,
                    "title", newTitle,
                    "streak", user.getStreak()
            ));

        } catch (Exception e) {
            System.err.println("❌ XP sync error: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Internal server error"));
        }
    }
}
