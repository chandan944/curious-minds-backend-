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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
// Removed JPA imports for Firestore migration

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.lang.management.ManagementFactory;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@SuppressWarnings("null")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final com.DSA.user.PointTransactionRepository pointTransactionRepository;
    private final com.google.cloud.firestore.Firestore firestore;
    private final com.DSA.common.OperationTracker operationTracker;
    private final org.springframework.cache.CacheManager cacheManager;

    @Value("${google.client.id}")
    private String googleClientId;

    // Define admin emails here (or move to application.properties)
    private static final List<String> ADMIN_EMAILS = Arrays.asList(
            "kanchanparajapati4@gmail.com",
            "chandanprajapati6307@gmail.com"
    );

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("service", "DSA Backend");
        response.put("version", "1.0.0");

        try {
            // 1. Flush any pending in-memory counts to Firestore to ensure 100% real-time accuracy
            operationTracker.flush();

            // 2. Fetch statistics documents from the last 30 days from Firestore
            String todayStr = LocalDate.now(ZoneId.of("UTC")).toString();
            String sevenDaysAgoStr = LocalDate.now(ZoneId.of("UTC")).minusDays(7).toString();
            String thirtyDaysAgoStr = LocalDate.now(ZoneId.of("UTC")).minusDays(30).toString();

            List<com.google.cloud.firestore.QueryDocumentSnapshot> docs = firestore.collection("operation_stats")
                    .whereGreaterThanOrEqualTo(com.google.cloud.firestore.FieldPath.documentId(), thirtyDaysAgoStr)
                    .get().get().getDocuments();

            // 3. Aggregate stats for 24h, 7d, 30d
            long reads24h = 0, writes24h = 0, deletes24h = 0, r2ClassA24h = 0, r2ClassB24h = 0;
            long reads7d = 0, writes7d = 0, deletes7d = 0, r2ClassA7d = 0, r2ClassB7d = 0;
            long reads30d = 0, writes30d = 0, deletes30d = 0, r2ClassA30d = 0, r2ClassB30d = 0;

            for (com.google.cloud.firestore.QueryDocumentSnapshot doc : docs) {
                String dateId = doc.getId();
                long reads = getLongVal(doc, "firestoreReads");
                long writes = getLongVal(doc, "firestoreWrites");
                long deletes = getLongVal(doc, "firestoreDeletes");
                long r2A = getLongVal(doc, "r2ClassA");
                long r2B = getLongVal(doc, "r2ClassB");

                // 24 Hours (Today)
                if (dateId.equals(todayStr)) {
                    reads24h += reads;
                    writes24h += writes;
                    deletes24h += deletes;
                    r2ClassA24h += r2A;
                    r2ClassB24h += r2B;
                }

                // 7 Days
                if (dateId.compareTo(sevenDaysAgoStr) >= 0) {
                    reads7d += reads;
                    writes7d += writes;
                    deletes7d += deletes;
                    r2ClassA7d += r2A;
                    r2ClassB7d += r2B;
                }

                // 30 Days (All fetched docs)
                reads30d += reads;
                writes30d += writes;
                deletes30d += deletes;
                r2ClassA30d += r2A;
                r2ClassB30d += r2B;
            }

            // 4. Build response payload
            Map<String, Object> resourceUsage = new LinkedHashMap<>();
            
            // Firestore Stats & Quotas
            Map<String, Object> firestoreStats = new LinkedHashMap<>();
            firestoreStats.put("dailyLimits", Map.of("reads", 50000, "writes", 20000, "deletes", 20000));
            firestoreStats.put("last24Hours", Map.of(
                    "reads", reads24h,
                    "writes", writes24h,
                    "deletes", deletes24h,
                    "readsRemaining", Math.max(0, 50000 - reads24h),
                    "writesRemaining", Math.max(0, 20000 - writes24h)
            ));
            firestoreStats.put("last7Days", Map.of("reads", reads7d, "writes", writes7d, "deletes", deletes7d));
            firestoreStats.put("last30Days", Map.of("reads", reads30d, "writes", writes30d, "deletes", deletes30d));
            resourceUsage.put("firestoreUsage", firestoreStats);

            // Cloudflare R2 Stats & Quotas
            Map<String, Object> r2Stats = new LinkedHashMap<>();
            r2Stats.put("monthlyLimits", Map.of("classAOperations", 1000000, "classBOperations", 10000000, "freeStorageGB", 10));
            r2Stats.put("last24Hours", Map.of("classA", r2ClassA24h, "classB", r2ClassB24h));
            r2Stats.put("last7Days", Map.of("classA", r2ClassA7d, "classB", r2ClassB7d));
            r2Stats.put("last30Days", Map.of(
                    "classA", r2ClassA30d, 
                    "classB", r2ClassB30d,
                    "classARemaining", Math.max(0, 1000000 - r2ClassA30d),
                    "classBRemaining", Math.max(0, 10000000 - r2ClassB30d)
            ));
            resourceUsage.put("r2Usage", r2Stats);

            // Caffeine Cache Statistics (Reads Saved!)
            Map<String, Object> cacheSavings = new LinkedHashMap<>();
            long totalEstimatedReadsSaved = 0;
            
            List<String> cacheNames = Arrays.asList("leaderboard_all", "leaderboard_month", "leaderboard_week", "user_profiles", "recommendations");
            Map<String, Object> details = new LinkedHashMap<>();
            
            for (String cacheName : cacheNames) {
                org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
                if (cache != null && cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache) {
                    com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache = 
                            (com.github.benmanes.caffeine.cache.Cache<?, ?>) cache.getNativeCache();
                    var stats = nativeCache.stats();
                    
                    long hits = stats.hitCount();
                    long misses = stats.missCount();
                    double hitRate = stats.hitRate();
                    
                    totalEstimatedReadsSaved += hits;
                    
                    details.put(cacheName, Map.of(
                            "hits", hits,
                            "misses", misses,
                            "hitRate", String.format("%.2f%%", hitRate * 100.0),
                            "estimatedReadsSaved", hits
                    ));
                }
            }
            
            cacheSavings.put("totalEstimatedReadsSaved", totalEstimatedReadsSaved);
            cacheSavings.put("estimatedMoneySaved", String.format("$%.4f", totalEstimatedReadsSaved * 0.000006)); // Firestore is $0.06 per 100k reads
            cacheSavings.put("caches", details);
            resourceUsage.put("cacheSavings", cacheSavings);

            // System Performance & Uptime Metrics
            Map<String, Object> system = new LinkedHashMap<>();
            long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
            system.put("uptime", formatUptime(uptimeMs));
            
            long freeMem = Runtime.getRuntime().freeMemory();
            long totalMem = Runtime.getRuntime().totalMemory();
            long maxMem = Runtime.getRuntime().maxMemory();
            
            system.put("memory", Map.of(
                    "usedMB", (totalMem - freeMem) / (1024 * 1024),
                    "totalMB", totalMem / (1024 * 1024),
                    "maxMB", maxMem / (1024 * 1024)
            ));
            resourceUsage.put("systemMetrics", system);

            response.put("resourceUsage", resourceUsage);

        } catch (Exception e) {
            System.err.println("⚠️ Failed to compile resource stats in health check: " + e.getMessage());
            response.put("resourceUsageError", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    private long getLongVal(com.google.cloud.firestore.QueryDocumentSnapshot doc, String fieldName) {
        Long val = doc.getLong(fieldName);
        return val != null ? val : 0;
    }

    private String formatUptime(long uptimeMs) {
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        return String.format("%d days, %d hours, %d minutes, %d seconds", 
                days, hours % 24, minutes % 60, seconds % 60);
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
                java.util.Map<String, Object> userMap = new java.util.HashMap<>();
                userMap.put("id", u.getId());
                userMap.put("idString", u.getIdString());
                userMap.put("email", u.getEmail());
                userMap.put("name", u.getName());
                userMap.put("imageUrl", u.getImageUrl() != null ? u.getImageUrl() : "");
                userMap.put("role", u.getRole().name());
                userMap.put("streak", u.getStreak());
                userMap.put("level", u.getLevel());
                userMap.put("points", u.getPoints());
                userMap.put("title", u.getTitle());
                userMap.put("isPrivateProfile", u.isPrivateProfile());
                userMap.put("isPremium", u.isPremium());

                return ResponseEntity.ok(Map.of(
                        "valid", true,
                        "user", userMap
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

            java.util.Map<String, Object> userMap = new java.util.HashMap<>();
            userMap.put("id", user.getId());
            userMap.put("idString", user.getIdString());
            userMap.put("email", user.getEmail());
            userMap.put("name", user.getName());
            userMap.put("imageUrl", user.getImageUrl() != null ? user.getImageUrl() : "");
            userMap.put("role", user.getRole().name());
            userMap.put("streak", user.getStreak());
            userMap.put("level", user.getLevel());
            userMap.put("points", user.getPoints());
            userMap.put("title", user.getTitle());
            userMap.put("isPrivateProfile", user.isPrivateProfile());
            userMap.put("isPremium", user.isPremium());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "token", jwtToken,
                    "user", userMap
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

    @PutMapping("/push-token")
    public ResponseEntity<?> updatePushToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> body) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Missing or invalid token"));
            }
            String token = authHeader.substring(7);
            Claims claims = jwtService.extractClaims(token);
            Long userId = claims.get("userId", Long.class);

            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Invalid token"));
            }

            String pushToken = body.get("expoPushToken");

            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                user.setExpoPushToken(pushToken);
                userRepository.save(user);
                return ResponseEntity.ok(Map.of("success", true, "message", "Push token updated"));
            } else {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/premium/sync")
    public ResponseEntity<?> syncPremiumStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Boolean> body) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Missing or invalid token"));
            }
            String token = authHeader.substring(7);
            Claims claims = jwtService.extractClaims(token);
            Long userId = claims.get("userId", Long.class);

            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Invalid token"));
            }

            Boolean isPremium = body.get("isPremium");
            if (isPremium == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "isPremium boolean is required"));
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                user.setPremium(isPremium);
                userRepository.save(user);
                System.out.println("💎 Synced premium status for user " + userId + " -> " + isPremium);
                return ResponseEntity.ok(Map.of("success", true, "isPremium", user.isPremium()));
            } else {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found"));
            }
        } catch (Exception e) {
            System.err.println("⚠️ Error syncing premium status: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/delete-account")
    @Transactional
    public ResponseEntity<?> deleteAccount(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Missing or invalid token"));
            }
            String token = authHeader.substring(7);
            Claims claims = jwtService.extractClaims(token);
            Long userId = claims.get("userId", Long.class);

            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Invalid token"));
            }

            // Verify user exists
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found"));
            }

            // 1. Delete Point Transactions
            deleteCollectionDocuments("point_transactions", "userId", userId);

            // 2. Delete Chat Messages (sent or received)
            deleteCollectionDocuments("chat_messages", "senderId", userId);
            deleteCollectionDocuments("chat_messages", "receiverId", userId);

            // 3. Delete Friendships (requester or addressee)
            deleteCollectionDocuments("friendships", "requesterId", userId);
            deleteCollectionDocuments("friendships", "addresseeId", userId);

            // 4. Delete Notifications (recipient or senderId)
            deleteCollectionDocuments("notifications", "recipientId", userId);
            deleteCollectionDocuments("notifications", "senderId", userId);

            // 5. Delete UserLikes (liker or liked user)
            deleteCollectionDocuments("user_likes", "likerId", userId);
            deleteCollectionDocuments("user_likes", "likedUserId", userId);

            // 6. Finally, delete the User
            userRepository.delete(user);

            return ResponseEntity.ok(Map.of("success", true, "message", "Account successfully deleted"));
        } catch (Exception e) {
            System.err.println("❌ Account Deletion Error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Internal Server Error during deletion"));
        }
    }

    private void deleteCollectionDocuments(String collectionName, String fieldName, Long userId) {
        try {
            var docs = firestore.collection(collectionName)
                    .whereEqualTo(fieldName, userId)
                    .get().get().getDocuments();
            
            if (!docs.isEmpty()) {
                var batch = firestore.batch();
                for (var doc : docs) {
                    batch.delete(doc.getReference());
                }
                batch.commit().get();
            }
        } catch (Exception e) {
            System.err.println("❌ Error deleting " + collectionName + " for " + fieldName + "=" + userId + ": " + e.getMessage());
        }
    }

    @PutMapping("/privacy")
    @CacheEvict(value = "user_profiles", allEntries = true)
    public ResponseEntity<?> updatePrivacy(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                           @RequestBody Map<String, Boolean> payload) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Missing or invalid token"));
            }
            String token = authHeader.substring(7);
            Claims claims = jwtService.extractClaims(token);
            Long userId = claims.get("userId", Long.class);

            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Invalid token"));
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found"));
            }

            Boolean isPrivate = payload.get("isPrivateProfile");
            if (isPrivate != null) {
                user.setPrivateProfile(isPrivate);
                userRepository.save(user);
            }

            return ResponseEntity.ok(Map.of("success", true, "isPrivateProfile", user.isPrivateProfile()));
        } catch (Exception e) {
            System.err.println("⚠️ Error updating privacy: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Internal Server Error"));
        }
    }

    @PutMapping("/avatar")
    @CacheEvict(value = "user_profiles", allEntries = true)
    public ResponseEntity<?> updateAvatar(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                           @RequestBody Map<String, String> payload) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Missing or invalid token"));
            }
            String token = authHeader.substring(7);
            Claims claims = jwtService.extractClaims(token);
            Long userId = claims.get("userId", Long.class);

            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Invalid token"));
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found"));
            }

            String imageUrl = payload.get("imageUrl");
            if (imageUrl != null) {
                user.setImageUrl(imageUrl);
                userRepository.save(user);
            }

            return ResponseEntity.ok(Map.of("success", true, "imageUrl", user.getImageUrl()));
        } catch (Exception e) {
            System.err.println("⚠️ Error updating avatar: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Internal Server Error"));
        }
    }

    @PutMapping("/bio")
    @CacheEvict(value = "user_profiles", allEntries = true)
    public ResponseEntity<?> updateBio(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                           @RequestBody Map<String, String> payload) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Missing or invalid token"));
            }
            String token = authHeader.substring(7);
            Claims claims = jwtService.extractClaims(token);
            Long userId = claims.get("userId", Long.class);

            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Invalid token"));
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found"));
            }

            String bio = payload.get("bio");
            if (bio != null) {
                // Optional length check: e.g., max 255 chars. We'll truncate or accept it. Let's truncate to 255 just in case.
                if (bio.length() > 255) {
                    bio = bio.substring(0, 255);
                }
                user.setBio(bio);
                userRepository.save(user);
            }

            return ResponseEntity.ok(Map.of("success", true, "bio", user.getBio()));
        } catch (Exception e) {
            System.err.println("⚠️ Error updating bio: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Internal Server Error"));
        }
    }

    private boolean isAdminEmail(String email) {
        return ADMIN_EMAILS.stream()
                .anyMatch(adminEmail -> adminEmail.equalsIgnoreCase(email));
    }
}
