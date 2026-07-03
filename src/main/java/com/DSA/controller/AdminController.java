package com.DSA.controller;

import com.DSA.user.User;
import com.DSA.user.UserRepository;
import com.DSA.common.ExpoNotificationService;
import com.DSA.social.Notification;
import com.DSA.social.NotificationRepository;
import com.DSA.social.NotificationType;
import com.DSA.config.ChatWebSocketHandler;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminController {

    private final UserRepository userRepository;
    private final ExpoNotificationService expoNotificationService;
    private final NotificationRepository notificationRepository;
    private final ChatWebSocketHandler chatWebSocketHandler;

    private static final String ALLOWED_ADMIN_EMAIL = "chandanprajapati6307@gmail.com";

    private boolean isNotAdmin(Authentication auth) {
        if (auth == null) return true;
        boolean hasAdminRole = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        String email = auth.getName();
        return !hasAdminRole && !ALLOWED_ADMIN_EMAIL.equalsIgnoreCase(email);
    }

    @PostMapping("/broadcast-notification")
    public ResponseEntity<?> broadcastNotification(
            Authentication authentication,
            @RequestBody Map<String, String> body) {

        if (isNotAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden: Only admins can send announcements"));
        }

        String title = body.get("title");
        String description = body.get("description");

        if (title == null || title.trim().isEmpty() || description == null || description.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Title and description are required"));
        }

        // Fetch all users
        List<User> users = userRepository.findAll();
        List<String> pushTokens = new java.util.ArrayList<>();
        int notifiedCount = 0;

        for (User u : users) {
            if (u == null) continue;

            // 1. Save a Notification record to Firestore for each user
            try {
                Notification notif = Notification.builder()
                        .recipient(u)
                        .type(NotificationType.ADMIN_BROADCAST)
                        .senderId(0L) // System/Admin
                        .senderName("Admin")
                        .message("📢 " + title + ": " + description)
                        .isRead(false)
                        .createdAt(Instant.now())
                        .build();
                notificationRepository.save(notif);
                notifiedCount++;

                // 2. Push WebSocket NOTIFICATION event to online users
                if (chatWebSocketHandler.isUserOnline(u.getId())) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "NOTIFICATION");
                    payload.addProperty("id", notif.getId());
                    payload.addProperty("notifType", NotificationType.ADMIN_BROADCAST.name());
                    payload.addProperty("senderId", 0L);
                    payload.addProperty("senderName", "Admin");
                    payload.addProperty("senderImage", "");
                    payload.addProperty("message", notif.getMessage());
                    payload.addProperty("isRead", false);
                    payload.addProperty("createdAt", notif.getCreatedAt().toString());
                    chatWebSocketHandler.sendToUser(u.getId(), payload);
                }
            } catch (Exception e) {
                System.err.println("⚠️ Failed to save broadcast notification for user " + u.getId() + ": " + e.getMessage());
            }

            // 3. Collect push tokens for batch Expo push
            if (u.getExpoPushToken() != null && !u.getExpoPushToken().trim().isEmpty()) {
                pushTokens.add(u.getExpoPushToken());
            }
        }

        // 4. Send Expo batch push notification with data payload
        if (!pushTokens.isEmpty()) {
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("type", "NOTIFICATION");
            data.put("notifType", NotificationType.ADMIN_BROADCAST.name());
            expoNotificationService.sendBatchPushNotifications(pushTokens, title, description, data);
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Broadcast sent to " + notifiedCount + " users (" + pushTokens.size() + " push devices)."));
    }
}
