package com.DSA.config;

import com.DSA.auth.JwtService;
import com.DSA.user.ChatMessage;
import com.DSA.user.ChatMessageRepository;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final Gson gson = new Gson();

    // Mapping from WebSocketSession to User ID
    private final Map<WebSocketSession, Long> sessionUserMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("🟢 New WebSocket connection established: " + session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long userId = sessionUserMap.remove(session);
        System.out.println("🔴 WebSocket connection closed: " + session.getId() + (userId != null ? " (User: " + userId + ")" : ""));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonObject payload = gson.fromJson(message.getPayload(), JsonObject.class);
            String type = payload.has("type") ? payload.get("type").getAsString() : "";

            // ── 1. Authentication ──────────────────────────────────────────────────
            if ("AUTH".equals(type)) {
                String token = payload.get("token").getAsString();
                Claims claims = jwtService.extractClaims(token);
                Long userId = claims.get("userId", Long.class);
                sessionUserMap.put(session, userId);
                
                JsonObject response = new JsonObject();
                response.addProperty("type", "AUTH_SUCCESS");
                response.addProperty("userId", userId);
                session.sendMessage(new TextMessage(response.toString()));
                System.out.println("✅ WebSocket Auth success for User ID: " + userId);
                return;
            }

            // Ensure authenticated before sending messages
            Long senderId = sessionUserMap.get(session);
            if (senderId == null) {
                session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Not authenticated"));
                return;
            }

            // ── 2. Handle Sending Message ──────────────────────────────────────────
            if ("SEND".equals(type)) {
                String target = payload.has("target") ? payload.get("target").getAsString() : "GLOBAL";
                String content = payload.get("content").getAsString();

                User sender = userRepository.findById(senderId).orElse(null);
                if (sender == null) return;

                ChatMessage chatMsg = new ChatMessage();
                chatMsg.setSender(sender);
                chatMsg.setContent(content);
                chatMsg.setTimestamp(LocalDateTime.now());

                if (payload.has("replyToId") && !payload.get("replyToId").isJsonNull()) {
                    chatMsg.setReplyToId(payload.get("replyToId").getAsLong());
                    chatMsg.setReplyToContent(payload.has("replyToContent") ? payload.get("replyToContent").getAsString() : null);
                    chatMsg.setReplyToSenderName(payload.has("replyToSenderName") ? payload.get("replyToSenderName").getAsString() : null);
                }

                User receiver = null;
                if (!"GLOBAL".equals(target)) {
                    receiver = userRepository.findById(Long.parseLong(target)).orElse(null);
                    chatMsg.setReceiver(receiver);
                }

                // Save to DB
                chatMessageRepository.save(chatMsg);

                // Format response
                JsonObject msgNode = new JsonObject();
                msgNode.addProperty("type", "MESSAGE");
                msgNode.addProperty("id", chatMsg.getId());
                msgNode.addProperty("senderId", sender.getId());
                msgNode.addProperty("senderName", sender.getName());
                msgNode.addProperty("senderImage", sender.getImageUrl());
                msgNode.addProperty("target", target);
                msgNode.addProperty("content", content);
                msgNode.addProperty("timestamp", chatMsg.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                msgNode.addProperty("status", chatMsg.getStatus());

                if (chatMsg.getReplyToId() != null) {
                    msgNode.addProperty("replyToId", chatMsg.getReplyToId());
                    msgNode.addProperty("replyToContent", chatMsg.getReplyToContent());
                    msgNode.addProperty("replyToSenderName", chatMsg.getReplyToSenderName());
                }

                TextMessage textMessage = new TextMessage(msgNode.toString());

                // Broadcast Logic
                if ("GLOBAL".equals(target)) {
                    // Send to everyone
                    for (WebSocketSession s : sessionUserMap.keySet()) {
                        if (s.isOpen()) s.sendMessage(textMessage);
                    }
                } else {
                    // Send to sender
                    if (session.isOpen()) session.sendMessage(textMessage);
                    // Send to receiver if they are online
                    if (receiver != null) {
                        for (Map.Entry<WebSocketSession, Long> entry : sessionUserMap.entrySet()) {
                            if (entry.getValue().equals(receiver.getId()) && entry.getKey().isOpen() && !entry.getKey().getId().equals(session.getId())) {
                                entry.getKey().sendMessage(textMessage);
                            }
                        }
                    }
                }
            }

            // ── 3. Handle Mark as Read ──────────────────────────────────────────────
            if ("MARK_READ".equals(type)) {
                Long messageId = payload.get("messageId").getAsLong();
                ChatMessage chatMsg = chatMessageRepository.findById(messageId).orElse(null);
                
                if (chatMsg != null && !"READ".equals(chatMsg.getStatus())) {
                    chatMsg.setStatus("READ");
                    chatMessageRepository.save(chatMsg);
                    
                    // Broadcast READ_RECEIPT to the original sender
                    JsonObject receiptNode = new JsonObject();
                    receiptNode.addProperty("type", "READ_RECEIPT");
                    receiptNode.addProperty("messageId", messageId);
                    receiptNode.addProperty("readerId", senderId); // The person who read it
                    
                    TextMessage receiptMsg = new TextMessage(receiptNode.toString());
                    
                    for (Map.Entry<WebSocketSession, Long> entry : sessionUserMap.entrySet()) {
                        if (entry.getValue().equals(chatMsg.getSender().getId()) && entry.getKey().isOpen()) {
                            entry.getKey().sendMessage(receiptMsg);
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Error processing WebSocket message: " + e.getMessage());
        }
    }

    // ── Public method for SocialService to push notifications to a user ──────
    public void sendToUser(Long userId, JsonObject payload) {
        TextMessage msg = new TextMessage(payload.toString());
        for (Map.Entry<WebSocketSession, Long> entry : sessionUserMap.entrySet()) {
            if (entry.getValue().equals(userId) && entry.getKey().isOpen()) {
                try {
                    entry.getKey().sendMessage(msg);
                } catch (Exception e) {
                    System.err.println("⚠️ Failed to send WS message to user " + userId + ": " + e.getMessage());
                }
            }
        }
    }

    public boolean isUserOnline(Long userId) {
        return sessionUserMap.containsValue(userId);
    }
}
