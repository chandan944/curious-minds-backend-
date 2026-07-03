package com.DSA.social;

import com.DSA.auth.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.cache.annotation.CacheEvict;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/social")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SocialController {

    private final SocialService socialService;
    private final NotificationRepository notificationRepository;
    private final JwtService jwtService;

    // ── Helper to extract userId from JWT ─────────────────────────────────────
    private Long getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        Claims claims = jwtService.extractClaims(token);
        return jwtService.getUserId(claims);
    }

    // ── 1. Toggle Like ─────────────────────────────────────────────────────────
    @PostMapping("/like/{targetUserId}")
    @CacheEvict(value = {"user_profiles", "recommendations"}, allEntries = true)
    public ResponseEntity<?> toggleLike(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long targetUserId) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        try {
            boolean isLiked = socialService.toggleLike(myId, targetUserId);
            long likeCount = socialService.getLikeCount(targetUserId);
            return ResponseEntity.ok(Map.of(
                    "liked", isLiked,
                    "likeCount", likeCount
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 2. Send Friend Request ─────────────────────────────────────────────────
    @PostMapping("/friend-request/{targetUserId}")
    @CacheEvict(value = {"user_profiles", "recommendations"}, allEntries = true)
    public ResponseEntity<?> sendFriendRequest(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long targetUserId) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        try {
            String result = socialService.sendFriendRequest(myId, targetUserId);
            return ResponseEntity.ok(Map.of("status", result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 3. Accept Friend Request ──────────────────────────────────────────────
    @PostMapping("/friend-accept/{requesterId}")
    @CacheEvict(value = {"user_profiles", "recommendations"}, allEntries = true)
    public ResponseEntity<?> acceptFriendRequest(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long requesterId) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        try {
            String result = socialService.acceptFriendRequest(myId, requesterId);
            return ResponseEntity.ok(Map.of("status", result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 4. Enhanced Profile (with social stats) ───────────────────────────────
    @GetMapping("/profile/{targetUserId}")
    public ResponseEntity<?> getSocialProfile(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long targetUserId) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Map<String, Object> profile = socialService.getCachedSocialProfile(targetUserId, myId);
        if (profile != null) {
            return ResponseEntity.ok(profile);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // ── 5. Get Notifications (Paginated) ──────────────────────────────────────
    @GetMapping("/notifications")
    public ResponseEntity<?> getNotifications(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Page<Notification> result = notificationRepository
                .findByRecipientIdOrderByCreatedAtDesc(myId, PageRequest.of(page, size));

        long unreadCount = notificationRepository.countByRecipientIdAndIsReadFalse(myId);

        return ResponseEntity.ok(Map.of(
                "content", result.getContent().stream().map(n -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", n.getId());
                    map.put("idString", n.getIdString());
                    map.put("type", n.getType().name());
                    map.put("senderId", n.getSenderId());
                    map.put("senderIdString", n.getSenderIdString());
                    map.put("senderName", n.getSenderName());
                    map.put("senderImage", n.getSenderImage() != null ? n.getSenderImage() : "");
                    map.put("message", n.getMessage());
                    map.put("isRead", n.isRead());
                    map.put("createdAt", n.getCreatedAt().toString());
                    return map;
                }).collect(Collectors.toList()),
                "totalPages", result.getTotalPages(),
                "unreadCount", unreadCount
        ));
    }

    // ── 6. Mark Single Notification Read ──────────────────────────────────────
    @PostMapping("/notifications/read/{notifId}")
    public ResponseEntity<?> markNotificationRead(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long notifId) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        return notificationRepository.findById(notifId).map(n -> {
            if (!n.getRecipient().getId().equals(myId)) {
                return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
            }
            n.setRead(true);
            notificationRepository.save(n);
            return ResponseEntity.ok(Map.of("success", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── 7. Mark All Notifications Read ────────────────────────────────────────
    @PostMapping("/notifications/read-all")
    @Transactional
    public ResponseEntity<?> markAllNotificationsRead(
            @RequestHeader("Authorization") String authHeader) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        int updated = notificationRepository.markAllAsReadForUser(myId);
        return ResponseEntity.ok(Map.of("markedRead", updated));
    }

    // ── 8. Unread Count ──────────────────────────────────────────────────────
    @GetMapping("/notifications/unread-count")
    public ResponseEntity<?> getUnreadCount(
            @RequestHeader("Authorization") String authHeader) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        long count = notificationRepository.countByRecipientIdAndIsReadFalse(myId);
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    // ── 9. Mutual Friend Recommendations ─────────────────────────────────────
    @GetMapping("/recommendations")
    public ResponseEntity<?> getRecommendations(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        return ResponseEntity.ok(socialService.getMutualFriendRecommendations(myId, page, size));
    }

    // ── 10. Search Users ─────────────────────────────────────────────────────
    @GetMapping("/search")
    public ResponseEntity<?> searchUsers(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        if (q.length() < 2) return ResponseEntity.ok(java.util.Collections.emptyList());

        return ResponseEntity.ok(socialService.searchUsers(myId, q, page, size));
    }

    // ── 11. Get Pending Requests ────────────────────────────────────────────
    @GetMapping("/requests/pending")
    public ResponseEntity<?> getPendingRequests(
            @RequestHeader("Authorization") String authHeader) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        return ResponseEntity.ok(socialService.getPendingRequests(myId));
    }

    // ── 12. Get Friends List ────────────────────────────────────────────────
    @GetMapping("/friends")
    public ResponseEntity<?> getFriendsList(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        return ResponseEntity.ok(socialService.getFriendsList(myId, page, size));
    }
}
