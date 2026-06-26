package com.DSA.social;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Lazy;
import lombok.RequiredArgsConstructor;
import com.DSA.common.IdGenerator;

import java.time.Instant;
import java.util.*;

@Repository
public class NotificationRepository {

    private final Firestore firestore;
    private final UserRepository userRepository;
    private final com.DSA.common.OperationTracker operationTracker;

    public NotificationRepository(Firestore firestore, @Lazy UserRepository userRepository, com.DSA.common.OperationTracker operationTracker) {
        this.firestore = firestore;
        this.userRepository = userRepository;
        this.operationTracker = operationTracker;
    }

    public Optional<Notification> findById(Long id) {
        if (id == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            DocumentSnapshot doc = firestore.collection("notifications")
                    .document(String.valueOf(id))
                    .get().get();
            if (doc.exists()) {
                return Optional.of(toEntity(doc));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in NotificationRepository.findById: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Notification save(Notification notification) {
        if (notification == null) return null;
        if (notification.getId() == null) {
            notification.setId(IdGenerator.generateId());
        }
        operationTracker.trackWrite();
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("id", notification.getId());
            data.put("recipientId", notification.getRecipient().getId());
            data.put("type", notification.getType().name());
            data.put("senderId", notification.getSenderId());
            data.put("senderName", notification.getSenderName());
            data.put("senderImage", notification.getSenderImage());
            data.put("message", notification.getMessage());
            data.put("isRead", notification.isRead());
            data.put("createdAt", com.google.cloud.Timestamp.ofTimeMicroseconds(
                    notification.getCreatedAt().getEpochSecond() * 1000000L + notification.getCreatedAt().getNano() / 1000L));

            firestore.collection("notifications")
                    .document(String.valueOf(notification.getId()))
                    .set(data).get();
        } catch (Exception e) {
            System.err.println("❌ Error in NotificationRepository.save: " + e.getMessage());
        }
        return notification;
    }

    public void delete(Notification notification) {
        if (notification != null && notification.getId() != null) {
            operationTracker.trackDelete();
            try {
                firestore.collection("notifications")
                        .document(String.valueOf(notification.getId()))
                        .delete().get();
            } catch (Exception e) {
                System.err.println("❌ Error in NotificationRepository.delete: " + e.getMessage());
            }
        }
    }

    public Page<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId, Pageable pageable) {
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();
        operationTracker.trackRead(); // Aggregation count query
        try {
            Query query = firestore.collection("notifications")
                    .whereEqualTo("recipientId", recipientId);

            long total = query.count().get().get().getCount();
            operationTracker.trackRead(); // Query call
            List<QueryDocumentSnapshot> docs = query
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit)
                    .offset(offset)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            List<Notification> notifications = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                notifications.add(toEntity(doc));
            }
            return new PageImpl<>(notifications, pageable, total);
        } catch (Exception e) {
            System.err.println("❌ Error in NotificationRepository.findByRecipientIdOrderByCreatedAtDesc: " + e.getMessage());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    public long countByRecipientIdAndIsReadFalse(Long recipientId) {
        if (recipientId == null) return 0;
        operationTracker.trackRead();
        try {
            return firestore.collection("notifications")
                    .whereEqualTo("recipientId", recipientId)
                    .whereEqualTo("isRead", false)
                    .count().get().get().getCount();
        } catch (Exception e) {
            System.err.println("❌ Error in NotificationRepository.countByRecipientIdAndIsReadFalse: " + e.getMessage());
            return 0;
        }
    }

    public int markAllAsReadForUser(Long userId) {
        if (userId == null) return 0;
        operationTracker.trackRead(); // Query call
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("notifications")
                    .whereEqualTo("recipientId", userId)
                    .whereEqualTo("isRead", false)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            if (docs.isEmpty()) return 0;

            operationTracker.trackWrites(docs.size());
            WriteBatch batch = firestore.batch();
            for (QueryDocumentSnapshot doc : docs) {
                batch.update(doc.getReference(), "isRead", true);
            }
            batch.commit().get();
            return docs.size();
        } catch (Exception e) {
            System.err.println("❌ Error in NotificationRepository.markAllAsReadForUser: " + e.getMessage());
            return 0;
        }
    }

    public void deleteByRecipientIdAndSenderIdAndType(Long recipientId, Long senderId, NotificationType type) {
        if (recipientId == null || senderId == null || type == null) return;
        operationTracker.trackRead(); // Query call
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("notifications")
                    .whereEqualTo("recipientId", recipientId)
                    .whereEqualTo("senderId", senderId)
                    .whereEqualTo("type", type.name())
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            if (docs.isEmpty()) return;

            operationTracker.trackDeletes(docs.size());
            WriteBatch batch = firestore.batch();
            for (QueryDocumentSnapshot doc : docs) {
                batch.delete(doc.getReference());
            }
            batch.commit().get();
        } catch (Exception e) {
            System.err.println("❌ Error in NotificationRepository.deleteByRecipientIdAndSenderIdAndType: " + e.getMessage());
        }
    }

    private Notification toEntity(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) return null;
        try {
            Long id = doc.getLong("id");
            Long recipientId = doc.getLong("recipientId");
            String typeStr = doc.getString("type");
            Long senderId = doc.getLong("senderId");
            String senderName = doc.getString("senderName");
            String senderImage = doc.getString("senderImage");
            String message = doc.getString("message");
            Boolean isRead = doc.getBoolean("isRead");
            com.google.cloud.Timestamp createdAtTimestamp = doc.getTimestamp("createdAt");

            User recipient = userRepository.findById(recipientId).orElse(null);
            if (recipient == null) {
                recipient = User.builder().id(recipientId).name("Deleted User").build();
            }

            return Notification.builder()
                    .id(id)
                    .recipient(recipient)
                    .type(NotificationType.valueOf(typeStr))
                    .senderId(senderId)
                    .senderName(senderName)
                    .senderImage(senderImage)
                    .message(message)
                    .isRead(isRead != null && isRead)
                    .createdAt(createdAtTimestamp != null ? createdAtTimestamp.toDate().toInstant() : Instant.now())
                    .build();
        } catch (Exception e) {
            System.err.println("❌ Error mapping Notification entity: " + e.getMessage());
            return null;
        }
    }
}
