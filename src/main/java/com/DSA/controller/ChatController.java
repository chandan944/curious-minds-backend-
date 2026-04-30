package com.DSA.controller;

import com.DSA.auth.JwtService;
import com.DSA.user.ChatMessage;
import com.DSA.user.ChatMessageRepository;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    // Helper to get User ID from JWT
    private Long getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        Claims claims = jwtService.extractClaims(token);
        return claims.get("userId", Long.class);
    }

    // ── 1. Fetch Global Chat History ───────────────────────────────────────────
    @GetMapping("/global")
    public ResponseEntity<?> getGlobalChat(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Page<ChatMessage> result = chatMessageRepository.findByReceiverIsNullOrderByTimestampDesc(PageRequest.of(page, size));
        
        List<Map<String, Object>> messages = result.getContent().stream().map(msg -> Map.<String, Object>of(
                "id", msg.getId(),
                "senderId", msg.getSender().getId(),
                "senderName", msg.getSender().getName(),
                "senderImage", msg.getSender().getImageUrl() != null ? msg.getSender().getImageUrl() : "",
                "target", "GLOBAL",
                "content", msg.getContent(),
                "timestamp", msg.getTimestamp().toString(),
                "status", msg.getStatus()
        )).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "content", messages,
                "totalPages", result.getTotalPages(),
                "currentPage", page
        ));
    }

    // ── 2. Fetch Direct Message History ────────────────────────────────────────
    @GetMapping("/direct/{targetId}")
    public ResponseEntity<?> getDirectChat(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long targetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).build();

        Page<ChatMessage> result = chatMessageRepository.findDirectMessages(myId, targetId, PageRequest.of(page, size));

        List<Map<String, Object>> messages = result.getContent().stream().map(msg -> Map.<String, Object>of(
                "id", msg.getId(),
                "senderId", msg.getSender().getId(),
                "senderName", msg.getSender().getName(),
                "senderImage", msg.getSender().getImageUrl() != null ? msg.getSender().getImageUrl() : "",
                "target", targetId.toString(),
                "content", msg.getContent(),
                "timestamp", msg.getTimestamp().toString(),
                "status", msg.getStatus()
        )).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "content", messages,
                "totalPages", result.getTotalPages(),
                "currentPage", page
        ));
    }

    // ── 3. Fetch Recent Chat Inbox ─────────────────────────────────────────────
    @GetMapping("/inbox")
    public ResponseEntity<?> getInbox(@RequestHeader("Authorization") String authHeader) {
        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).build();

        List<User> partners = chatMessageRepository.findRecentChatPartners(myId);
        // Exclude self just in case
        partners.removeIf(u -> u.getId().equals(myId));

        List<Map<String, Object>> inbox = partners.stream().map(u -> Map.<String, Object>of(
                "id", u.getId(),
                "name", u.getName(),
                "imageUrl", u.getImageUrl() != null ? u.getImageUrl() : "",
                "level", u.getLevel(),
                "title", u.getTitle()
        )).collect(Collectors.toList());

        return ResponseEntity.ok(inbox);
    }

    // ── 4. Fetch User Profile for Modal ────────────────────────────────────────
    @GetMapping("/profile/{id}")
    public ResponseEntity<?> getUserProfile(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(u -> ResponseEntity.ok(Map.of(
                        "id", u.getId(),
                        "name", u.getName(),
                        "imageUrl", u.getImageUrl() != null ? u.getImageUrl() : "",
                        "level", u.getLevel(),
                        "points", u.getPoints(),
                        "streak", u.getStreak(),
                        "title", u.getTitle()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
