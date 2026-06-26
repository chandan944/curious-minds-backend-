package com.DSA.user;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.Filter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Lazy;
import lombok.RequiredArgsConstructor;
import com.DSA.common.IdGenerator;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Repository
public class ChatMessageRepository {

    private final Firestore firestore;
    private final UserRepository userRepository;
    private final com.DSA.common.OperationTracker operationTracker;

    // Memory-only queue to store the last 100 global chat messages (0 reads optimization!)
    private final ConcurrentLinkedDeque<ChatMessage> globalChatQueue = new ConcurrentLinkedDeque<>();

    public ChatMessageRepository(Firestore firestore, @Lazy UserRepository userRepository, com.DSA.common.OperationTracker operationTracker) {
        this.firestore = firestore;
        this.userRepository = userRepository;
        this.operationTracker = operationTracker;
    }

    @PostConstruct
    public void init() {
        try {
            System.out.println("📥 Pre-loading global chat history into memory...");
            operationTracker.trackRead(); // Query call
            List<QueryDocumentSnapshot> docs = firestore.collection("chat_messages")
                    .whereEqualTo("receiverId", null)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(100)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            List<ChatMessage> list = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                ChatMessage msg = toEntity(doc);
                if (msg != null) {
                    list.add(msg);
                }
            }
            // Reverse so oldest is first in the deque
            Collections.reverse(list);
            globalChatQueue.addAll(list);
            System.out.println("✅ Successfully loaded " + globalChatQueue.size() + " global chat messages into memory.");
        } catch (Exception e) {
            System.err.println("⚠️ Failed to load global chat history: " + e.getMessage());
        }
    }

    public Optional<ChatMessage> findById(Long id) {
        if (id == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            DocumentSnapshot doc = firestore.collection("chat_messages")
                    .document(String.valueOf(id))
                    .get().get();
            if (doc.exists()) {
                return Optional.of(toEntity(doc));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in ChatMessageRepository.findById: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<ChatMessage> findByMessageId(String messageId) {
        if (messageId == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("chat_messages")
                    .whereEqualTo("messageId", messageId)
                    .limit(1)
                    .get().get().getDocuments();
            if (!docs.isEmpty()) {
                operationTracker.trackRead();
                return Optional.of(toEntity(docs.get(0)));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in ChatMessageRepository.findByMessageId: " + e.getMessage());
        }
        return Optional.empty();
    }

    public ChatMessage save(ChatMessage chatMsg) {
        if (chatMsg == null) return null;
        if (chatMsg.getId() == null) {
            chatMsg.setId(IdGenerator.generateId());
        }
        if (chatMsg.getTimestamp() == null) {
            chatMsg.setTimestamp(Instant.now());
        }
        operationTracker.trackWrite();
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("id", chatMsg.getId());
            data.put("senderId", chatMsg.getSender().getId());
            data.put("receiverId", chatMsg.getReceiver() != null ? chatMsg.getReceiver().getId() : null);
            data.put("content", chatMsg.getContent());
            data.put("timestamp", com.google.cloud.Timestamp.ofTimeMicroseconds(
                    chatMsg.getTimestamp().getEpochSecond() * 1000000L + chatMsg.getTimestamp().getNano() / 1000L));
            data.put("messageId", chatMsg.getMessageId());
            data.put("replyToId", chatMsg.getReplyToId());
            data.put("replyToContent", chatMsg.getReplyToContent());
            data.put("replyToSenderName", chatMsg.getReplyToSenderName());
            data.put("status", chatMsg.getStatus());

            // Save to Firestore
            firestore.collection("chat_messages")
                    .document(String.valueOf(chatMsg.getId()))
                    .set(data).get();

            // If global chat, update memory queue
            if (chatMsg.getReceiver() == null) {
                globalChatQueue.add(chatMsg);
                while (globalChatQueue.size() > 100) {
                    globalChatQueue.pollFirst();
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error in ChatMessageRepository.save: " + e.getMessage());
        }
        return chatMsg;
    }

    public Page<ChatMessage> findByReceiverIsNullOrderByTimestampDesc(Pageable pageable) {
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();

        // Caching: serve from memory queue if it covers the request (0 reads cost!)
        if (offset + limit <= globalChatQueue.size()) {
            List<ChatMessage> list = new ArrayList<>(globalChatQueue);
            Collections.reverse(list); // newest first

            int start = offset;
            int end = Math.min(start + limit, list.size());
            List<ChatMessage> sub = list.subList(start, end);
            return new PageImpl<>(sub, pageable, globalChatQueue.size());
        }

        // Fallback to Firestore if querying deeper history
        operationTracker.trackRead(); // Aggregation count query
        try {
            Query query = firestore.collection("chat_messages")
                    .whereEqualTo("receiverId", null);

            long total = query.count().get().get().getCount();
            operationTracker.trackRead(); // Query call
            List<QueryDocumentSnapshot> docs = query
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(limit)
                    .offset(offset)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            List<ChatMessage> messages = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                messages.add(toEntity(doc));
            }
            return new PageImpl<>(messages, pageable, total);
        } catch (Exception e) {
            System.err.println("❌ Error in ChatMessageRepository.findByReceiverIsNullOrderByTimestampDesc: " + e.getMessage());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    public Page<ChatMessage> findDirectMessages(Long user1, Long user2, Pageable pageable) {
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();
        operationTracker.trackRead(); // Aggregation count query
        try {
            Filter filter1 = Filter.and(Filter.equalTo("senderId", user1), Filter.equalTo("receiverId", user2));
            Filter filter2 = Filter.and(Filter.equalTo("senderId", user2), Filter.equalTo("receiverId", user1));
            
            Query query = firestore.collection("chat_messages")
                    .where(Filter.or(filter1, filter2));

            long total = query.count().get().get().getCount();
            operationTracker.trackRead(); // Query call
            List<QueryDocumentSnapshot> docs = query
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(limit)
                    .offset(offset)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            List<ChatMessage> messages = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                messages.add(toEntity(doc));
            }
            return new PageImpl<>(messages, pageable, total);
        } catch (Exception e) {
            System.err.println("❌ Error in ChatMessageRepository.findDirectMessages: " + e.getMessage());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    public List<User> findRecentChatPartners(Long userId) {
        Set<Long> partnerIds = new LinkedHashSet<>(); // preserves insertion/recency order
        operationTracker.trackRead(); // Query call
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("chat_messages")
                    .where(Filter.or(
                            Filter.equalTo("senderId", userId),
                            Filter.equalTo("receiverId", userId)
                    ))
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(100)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            for (QueryDocumentSnapshot doc : docs) {
                Long senderId = doc.getLong("senderId");
                Long receiverId = doc.getLong("receiverId");
                if (senderId != null && receiverId != null) {
                    if (senderId.equals(userId)) {
                        partnerIds.add(receiverId);
                    } else {
                        partnerIds.add(senderId);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error in ChatMessageRepository.findRecentChatPartners: " + e.getMessage());
        }

        List<User> partners = new ArrayList<>();
        for (Long partnerId : partnerIds) {
            userRepository.findById(partnerId).ifPresent(partners::add);
        }
        return partners;
    }

    public int countUnreadMessages(Long userId) {
        if (userId == null) return 0;
        operationTracker.trackRead();
        try {
            return (int) firestore.collection("chat_messages")
                    .whereEqualTo("receiverId", userId)
                    .whereNotEqualTo("status", "READ")
                    .count().get().get().getCount();
        } catch (Exception e) {
            System.err.println("❌ Error in ChatMessageRepository.countUnreadMessages: " + e.getMessage());
            return 0;
        }
    }

    public List<Object[]> countUnreadGroupedBySender(Long userId) {
        Map<Long, Long> counts = new HashMap<>();
        operationTracker.trackRead(); // Query call
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("chat_messages")
                    .whereEqualTo("receiverId", userId)
                    .whereNotEqualTo("status", "READ")
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            for (QueryDocumentSnapshot doc : docs) {
                Long senderId = doc.getLong("senderId");
                if (senderId != null) {
                    counts.put(senderId, counts.getOrDefault(senderId, 0L) + 1);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error in ChatMessageRepository.countUnreadGroupedBySender: " + e.getMessage());
        }

        List<Object[]> result = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : counts.entrySet()) {
            result.add(new Object[]{entry.getKey(), entry.getValue()});
        }
        return result;
    }

    private ChatMessage toEntity(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) return null;
        try {
            Long id = doc.getLong("id");
            Long senderId = doc.getLong("senderId");
            Long receiverId = doc.getLong("receiverId");
            String content = doc.getString("content");
            com.google.cloud.Timestamp timestampVal = doc.getTimestamp("timestamp");
            String messageId = doc.getString("messageId");
            Long replyToId = doc.getLong("replyToId");
            String replyToContent = doc.getString("replyToContent");
            String replyToSenderName = doc.getString("replyToSenderName");
            String status = doc.getString("status");

            User sender = userRepository.findById(senderId).orElse(null);
            User receiver = receiverId != null ? userRepository.findById(receiverId).orElse(null) : null;

            if (sender == null) {
                sender = User.builder().id(senderId).name("Deleted User").build();
            }

            return ChatMessage.builder()
                    .id(id)
                    .sender(sender)
                    .receiver(receiver)
                    .content(content)
                    .timestamp(timestampVal != null ? timestampVal.toDate().toInstant() : Instant.now())
                    .messageId(messageId)
                    .replyToId(replyToId)
                    .replyToContent(replyToContent)
                    .replyToSenderName(replyToSenderName)
                    .status(status != null ? status : "DELIVERED")
                    .build();
        } catch (Exception e) {
            System.err.println("❌ Error mapping ChatMessage entity: " + e.getMessage());
            return null;
        }
    }
}
