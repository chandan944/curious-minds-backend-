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
    private final com.DSA.config.ChatWebSocketHandler chatWebSocketHandler;

    // Helper to get User ID from JWT
    private Long getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        Claims claims = jwtService.extractClaims(token);
        return jwtService.getUserId(claims);
    }

    // ── 1. Fetch Global Chat History ───────────────────────────────────────────
    @GetMapping("/global")
    public ResponseEntity<?> getGlobalChat(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Page<ChatMessage> result = chatMessageRepository.findByReceiverIsNullOrderByTimestampDesc(PageRequest.of(page, size));
        
        List<Map<String, Object>> messages = result.getContent().stream().map(msg -> {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", msg.getId());
            if (msg.getMessageId() != null) {
                map.put("messageId", msg.getMessageId());
            }
            map.put("senderId", msg.getSender().getId());
            map.put("senderIdString", msg.getSender().getIdString());
            map.put("receiverId", null);
            map.put("senderName", msg.getSender().getName());
            map.put("senderImage", msg.getSender().getImageUrl() != null ? msg.getSender().getImageUrl() : "");
            map.put("target", "GLOBAL");
            map.put("content", msg.getContent());
            map.put("timestamp", msg.getTimestamp().toString());
            map.put("status", msg.getStatus());
            if (msg.getReplyToId() != null) {
                map.put("replyToId", msg.getReplyToId());
                map.put("replyToContent", msg.getReplyToContent());
                map.put("replyToSenderName", msg.getReplyToSenderName());
            }
            return map;
        }).collect(Collectors.toList());

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

        List<Map<String, Object>> messages = result.getContent().stream().map(msg -> {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", msg.getId());
            if (msg.getMessageId() != null) {
                map.put("messageId", msg.getMessageId());
            }
            map.put("senderId", msg.getSender().getId());
            map.put("senderIdString", msg.getSender().getIdString());
            map.put("receiverId", msg.getReceiver() != null ? msg.getReceiver().getId() : null);
            map.put("receiverIdString", msg.getReceiver() != null ? msg.getReceiver().getIdString() : null);
            map.put("senderName", msg.getSender().getName());
            map.put("senderImage", msg.getSender().getImageUrl() != null ? msg.getSender().getImageUrl() : "");
            map.put("target", targetId.toString());
            map.put("content", msg.getContent());
            map.put("timestamp", msg.getTimestamp().toString());
            map.put("status", msg.getStatus());
            if (msg.getReplyToId() != null) {
                map.put("replyToId", msg.getReplyToId());
                map.put("replyToContent", msg.getReplyToContent());
                map.put("replyToSenderName", msg.getReplyToSenderName());
            }
            return map;
        }).collect(Collectors.toList());

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

        // Batch fetch all unread message counts grouped by sender to resolve N+1 query loops
        List<Object[]> unreadCounts = chatMessageRepository.countUnreadGroupedBySender(myId);
        Map<Long, Long> unreadMap = unreadCounts.stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue()
                ));

        List<Map<String, Object>> inbox = partners.stream().map(u -> {
                Map<String, Object> map = new java.util.HashMap<>();
                map.put("id", u.getId());
                map.put("idString", u.getIdString());
                map.put("name", u.getName());
                map.put("imageUrl", u.getImageUrl() != null ? u.getImageUrl() : "");
                map.put("level", u.getLevel());
                map.put("title", u.getTitle() != null ? u.getTitle() : "Curious Kid");
                map.put("unreadCount", unreadMap.getOrDefault(u.getId(), 0L).intValue());

                // Fetch last message details
                Page<ChatMessage> lastMsgPage = chatMessageRepository.findDirectMessages(myId, u.getId(), PageRequest.of(0, 1));
                if (!lastMsgPage.getContent().isEmpty()) {
                    ChatMessage lastMsg = lastMsgPage.getContent().get(0);
                    map.put("lastMessage", lastMsg.getContent());
                    map.put("lastTimestamp", lastMsg.getTimestamp().toEpochMilli());
                } else {
                    map.put("lastMessage", "");
                    map.put("lastTimestamp", 0L);
                }
                return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(inbox);
    }

    // ── 4. Fetch User Profile for Modal ────────────────────────────────────────
    @GetMapping("/profile/{id}")
    public ResponseEntity<?> getUserProfile(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(u -> ResponseEntity.ok(Map.of(
                        "id", u.getId(),
                        "idString", u.getIdString(),
                        "name", u.getName(),
                        "imageUrl", u.getImageUrl() != null ? u.getImageUrl() : "",
                        "level", u.getLevel(),
                        "points", u.getPoints(),
                        "streak", u.getStreak(),
                        "title", u.getTitle()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── 5. Fetch Online Status ──────────────────────────────────────────
    @GetMapping("/online/{userId}")
    public ResponseEntity<Boolean> isUserOnline(@PathVariable Long userId) {
        return ResponseEntity.ok(chatWebSocketHandler.isUserOnline(userId));
    }

    // ── 5.5 Bulk Mark Messages as Read ───────────────────────────────────────
    @PostMapping("/read/{senderId}")
    public ResponseEntity<?> markAllAsRead(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long senderId) {
        
        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).build();

        chatMessageRepository.markAllAsRead(senderId, myId);
        return ResponseEntity.ok().build();
    }

    // ── 6. Fetch Unread Chat Messages Count ──────────────────────────────────
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Integer>> getUnreadChatCount(@RequestHeader("Authorization") String authHeader) {
        Long myId = getUserIdFromToken(authHeader);
        if (myId == null) return ResponseEntity.status(401).build();
        int count = chatMessageRepository.countUnreadMessages(myId);
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }
}
