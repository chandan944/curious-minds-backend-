package com.DSA.controller;

import com.DSA.user.User;
import com.DSA.user.UserRepository;
import com.DSA.common.ExpoNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminController {

    private final UserRepository userRepository;
    private final ExpoNotificationService expoNotificationService;

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

        // Fetch all users to extract Expo push tokens
        List<User> users = userRepository.findAll();
        List<String> pushTokens = users.stream()
                .filter(Objects::nonNull)
                .map(u -> u.getExpoPushToken())
                .filter(Objects::nonNull)
                .filter(token -> !token.trim().isEmpty())
                .collect(Collectors.toList());

        if (pushTokens.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", true, "message", "No active devices found to receive push notifications."));
        }

        // Broadcast to devices via Expo Service (Expo handles payloads up to 100 at a time)
        expoNotificationService.sendBatchPushNotifications(pushTokens, title, description, null);

        return ResponseEntity.ok(Map.of("success", true, "message", "Broadcast sent successfully to " + pushTokens.size() + " devices."));
    }
}
