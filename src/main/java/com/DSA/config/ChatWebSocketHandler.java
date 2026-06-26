package com.DSA.config;

import com.DSA.auth.JwtService;
import com.DSA.user.ChatMessage;
import com.DSA.user.ChatMessageRepository;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import com.DSA.common.ExpoNotificationService;
import com.DSA.social.FriendshipRepository;
import com.DSA.social.FriendshipStatus;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ExpoNotificationService expoNotificationService;
    private final FriendshipRepository friendshipRepository;
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

                // Extract client-generated messageId if present
                if (payload.has("messageId") && !payload.get("messageId").isJsonNull()) {
                    chatMsg.setMessageId(payload.get("messageId").getAsString());
                }

                // Extract client-generated timestamp if present
                Instant timestamp = Instant.now();
                if (payload.has("timestamp") && !payload.get("timestamp").isJsonNull()) {
                    try {
                        timestamp = Instant.ofEpochMilli(payload.get("timestamp").getAsLong());
                    } catch (Exception e) {
                        try {
                            timestamp = Instant.parse(payload.get("timestamp").getAsString());
                        } catch (Exception ex) {
                            timestamp = Instant.now();
                        }
                    }
                }
                chatMsg.setTimestamp(timestamp);

                if (payload.has("replyToId") && !payload.get("replyToId").isJsonNull()) {
                    chatMsg.setReplyToId(payload.get("replyToId").getAsLong());
                    chatMsg.setReplyToContent(payload.has("replyToContent") ? payload.get("replyToContent").getAsString() : null);
                    chatMsg.setReplyToSenderName(payload.has("replyToSenderName") ? payload.get("replyToSenderName").getAsString() : null);
                }

                User receiver = null;
                if (!"GLOBAL".equals(target)) {
                    receiver = userRepository.findById(Long.parseLong(target)).orElse(null);
                    
                    if (receiver != null) {
                        // Check privacy setting
                        if (receiver.isPrivateProfile()) {
                            // Are they friends?
                            boolean isFriend = friendshipRepository.findBetweenUsers(sender.getId(), receiver.getId())
                                .map(f -> f.getStatus() == FriendshipStatus.ACCEPTED)
                                .orElse(false);
                            
                            // Let Admins bypass this, or if it's not a friend, block it.
                            if (!isFriend && !sender.getRole().name().equals("ADMIN")) {
                                JsonObject errorNode = new JsonObject();
                                errorNode.addProperty("type", "MESSAGE_ERROR");
                                errorNode.addProperty("message", "This user's profile is private. You must be friends to send them a message.");
                                session.sendMessage(new TextMessage(errorNode.toString()));
                                return; // Stop processing, don't save or forward.
                            }
                        }
                        chatMsg.setReceiver(receiver);
                    }
                }

                // Save to DB
                chatMessageRepository.save(chatMsg);

                // Send DELIVERED confirmation ACK back to sender immediately for 1:1 chats
                if (!"GLOBAL".equals(target) && chatMsg.getMessageId() != null) {
                    JsonObject deliveredAck = new JsonObject();
                    deliveredAck.addProperty("type", "DELIVERED");
                    deliveredAck.addProperty("messageId", chatMsg.getMessageId());
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(deliveredAck.toString()));
                    }
                }

                // Format response
                JsonObject msgNode = new JsonObject();
                msgNode.addProperty("type", "MESSAGE");
                msgNode.addProperty("id", chatMsg.getId());
                if (chatMsg.getMessageId() != null) {
                    msgNode.addProperty("messageId", chatMsg.getMessageId());
                }
                msgNode.addProperty("senderId", sender.getId());
                msgNode.addProperty("senderIdString", sender.getIdString());
                msgNode.addProperty("senderName", sender.getName());
                msgNode.addProperty("senderImage", sender.getImageUrl());
                msgNode.addProperty("target", target);
                msgNode.addProperty("content", content);
                msgNode.addProperty("timestamp", chatMsg.getTimestamp().toString());
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
                    boolean receiverIsOnline = false;
                    if (receiver != null) {
                        for (Map.Entry<WebSocketSession, Long> entry : sessionUserMap.entrySet()) {
                            if (entry.getValue().equals(receiver.getId()) && entry.getKey().isOpen() && !entry.getKey().getId().equals(session.getId())) {
                                entry.getKey().sendMessage(textMessage);
                                receiverIsOnline = true;
                            }
                        }
                        
                        // Send Push Notification if receiver is not online (or always, depending on preference)
                        if (!receiverIsOnline && receiver.getExpoPushToken() != null && !receiver.getExpoPushToken().isEmpty()) {
                            Map<String, Object> data = new java.util.HashMap<>();
                            data.put("type", "CHAT_MESSAGE");
                            data.put("senderId", sender.getId());
                            data.put("senderIdString", sender.getIdString());
                            data.put("messageId", chatMsg.getMessageId() != null ? chatMsg.getMessageId() : chatMsg.getId().toString());
                            
                            expoNotificationService.sendPushNotification(
                                receiver.getExpoPushToken(),
                                sender.getName(),
                                content,
                                data
                            );
                        }
                    }
                }
            }

            // ── 3. Handle Mark as Read ──────────────────────────────────────────────
            if ("MARK_READ".equals(type)) {
                String msgIdStr = payload.has("messageId") && !payload.get("messageId").isJsonNull()
                    ? payload.get("messageId").getAsString() : null;

                if (msgIdStr != null) {
                    ChatMessage chatMsg = chatMessageRepository.findByMessageId(msgIdStr).orElse(null);

                    if (chatMsg == null) {
                        try {
                            Long id = Long.parseLong(msgIdStr);
                            chatMsg = chatMessageRepository.findById(id).orElse(null);
                        } catch (NumberFormatException e) {
                            // Ignored
                        }
                    }

                    if (chatMsg != null && !"READ".equals(chatMsg.getStatus())) {
                        chatMsg.setStatus("READ");
                        chatMessageRepository.save(chatMsg);

                        // Broadcast READ_RECEIPT to the original sender
                        JsonObject receiptNode = new JsonObject();
                        receiptNode.addProperty("type", "READ_RECEIPT");
                        receiptNode.addProperty("messageId", chatMsg.getMessageId() != null ? chatMsg.getMessageId() : chatMsg.getId().toString());
                        receiptNode.addProperty("readerId", senderId); // The person who read it

                        TextMessage receiptMsg = new TextMessage(receiptNode.toString());

                        for (Map.Entry<WebSocketSession, Long> entry : sessionUserMap.entrySet()) {
                            if (entry.getValue().equals(chatMsg.getSender().getId()) && entry.getKey().isOpen()) {
                                entry.getKey().sendMessage(receiptMsg);
                            }
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
