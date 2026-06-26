package com.DSA.user;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.QueryDocumentSnapshot;
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
public class PointTransactionRepository {

    private final Firestore firestore;
    private final UserRepository userRepository;
    private final com.DSA.common.OperationTracker operationTracker;

    public PointTransactionRepository(Firestore firestore, @Lazy UserRepository userRepository, com.DSA.common.OperationTracker operationTracker) {
        this.firestore = firestore;
        this.userRepository = userRepository;
        this.operationTracker = operationTracker;
    }

    public Optional<PointTransaction> findById(Long id) {
        if (id == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            DocumentSnapshot doc = firestore.collection("point_transactions")
                    .document(String.valueOf(id))
                    .get().get();
            if (doc.exists()) {
                return Optional.of(toEntity(doc));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in PointTransactionRepository.findById: " + e.getMessage());
        }
        return Optional.empty();
    }

    public PointTransaction save(PointTransaction pt) {
        if (pt == null) return null;
        if (pt.getId() == null) {
            pt.setId(IdGenerator.generateId());
        }
        if (pt.getEarnedAt() == null) {
            pt.setEarnedAt(Instant.now());
        }
        operationTracker.trackWrite();
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("id", pt.getId());
            data.put("userId", pt.getUser().getId());
            data.put("amount", pt.getAmount());
            data.put("earnedAt", com.google.cloud.Timestamp.ofTimeMicroseconds(
                    pt.getEarnedAt().getEpochSecond() * 1000000L + pt.getEarnedAt().getNano() / 1000L));
            data.put("reason", pt.getReason());

            firestore.collection("point_transactions")
                    .document(String.valueOf(pt.getId()))
                    .set(data).get();
        } catch (Exception e) {
            System.err.println("❌ Error in PointTransactionRepository.save: " + e.getMessage());
        }
        return pt;
    }

    public void delete(PointTransaction pt) {
        if (pt != null && pt.getId() != null) {
            operationTracker.trackDelete();
            try {
                firestore.collection("point_transactions")
                        .document(String.valueOf(pt.getId()))
                        .delete().get();
            } catch (Exception e) {
                System.err.println("❌ Error in PointTransactionRepository.delete: " + e.getMessage());
            }
        }
    }

    public Page<Object[]> findTopUsersByPointsSince(Instant from, Pageable pageable) {
        operationTracker.trackRead(); // Query call
        try {
            com.google.cloud.Timestamp ts = com.google.cloud.Timestamp.ofTimeMicroseconds(
                    from.getEpochSecond() * 1000000L + from.getNano() / 1000L);

            List<QueryDocumentSnapshot> docs = firestore.collection("point_transactions")
                    .whereGreaterThanOrEqualTo("earnedAt", ts)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            Map<Long, Integer> userPoints = new HashMap<>();
            for (QueryDocumentSnapshot doc : docs) {
                Long uId = doc.getLong("userId");
                Long amount = doc.getLong("amount");
                if (uId != null && amount != null) {
                    userPoints.put(uId, userPoints.getOrDefault(uId, 0) + amount.intValue());
                }
            }

            // Convert to rows: [userId, name, imageUrl, level, title, totalPoints]
            List<Object[]> rows = new ArrayList<>();
            for (Map.Entry<Long, Integer> entry : userPoints.entrySet()) {
                Long uId = entry.getKey();
                int totalPoints = entry.getValue();

                User u = userRepository.findById(uId).orElse(null);
                if (u != null) {
                    rows.add(new Object[]{
                            uId,
                            u.getName(),
                            u.getImageUrl() != null ? u.getImageUrl() : "",
                            u.getLevel(),
                            u.getTitle(),
                            totalPoints
                    });
                }
            }

            // Sort by totalPoints DESC
            rows.sort((r1, r2) -> Integer.compare((Integer) r2[5], (Integer) r1[5]));

            int total = rows.size();
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), total);

            List<Object[]> pageContent = (start < total) ? rows.subList(start, end) : Collections.emptyList();
            return new PageImpl<>(pageContent, pageable, total);
        } catch (Exception e) {
            System.err.println("❌ Error in PointTransactionRepository.findTopUsersByPointsSince: " + e.getMessage());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    public Integer getTotalPointsForUser(Long userId) {
        if (userId == null) return 0;
        operationTracker.trackRead(); // Query call
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("point_transactions")
                    .whereEqualTo("userId", userId)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            int sum = 0;
            for (QueryDocumentSnapshot doc : docs) {
                Long amount = doc.getLong("amount");
                if (amount != null) {
                    sum += amount.intValue();
                }
            }
            return sum;
        } catch (Exception e) {
            System.err.println("❌ Error in PointTransactionRepository.getTotalPointsForUser: " + e.getMessage());
            return 0;
        }
    }

    private PointTransaction toEntity(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) return null;
        try {
            Long id = doc.getLong("id");
            Long userId = doc.getLong("userId");
            Long amount = doc.getLong("amount");
            com.google.cloud.Timestamp earnedAtVal = doc.getTimestamp("earnedAt");
            String reason = doc.getString("reason");

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                user = User.builder().id(userId).name("Deleted User").build();
            }

            return PointTransaction.builder()
                    .id(id)
                    .user(user)
                    .amount(amount != null ? amount.intValue() : 0)
                    .earnedAt(earnedAtVal != null ? earnedAtVal.toDate().toInstant() : Instant.now())
                    .reason(reason)
                    .build();
        } catch (Exception e) {
            System.err.println("❌ Error mapping PointTransaction entity: " + e.getMessage());
            return null;
        }
    }
}
