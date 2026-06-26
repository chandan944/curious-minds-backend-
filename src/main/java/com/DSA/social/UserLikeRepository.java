package com.DSA.social;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentSnapshot;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Lazy;
import lombok.RequiredArgsConstructor;
import com.DSA.common.IdGenerator;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserLikeRepository {

    private final Firestore firestore;
    private final UserRepository userRepository;
    private final com.DSA.common.OperationTracker operationTracker;

    public UserLikeRepository(Firestore firestore, @Lazy UserRepository userRepository, com.DSA.common.OperationTracker operationTracker) {
        this.firestore = firestore;
        this.userRepository = userRepository;
        this.operationTracker = operationTracker;
    }

    private String getDocId(Long likerId, Long likedUserId) {
        return likerId + "_" + likedUserId;
    }

    public Optional<UserLike> findById(Long id) {
        if (id == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            var docs = firestore.collection("user_likes")
                    .whereEqualTo("id", id)
                    .limit(1)
                    .get().get().getDocuments();
            if (!docs.isEmpty()) {
                operationTracker.trackRead();
                return Optional.of(toEntity(docs.get(0)));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in UserLikeRepository.findById: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<UserLike> findByLikerIdAndLikedUserId(Long likerId, Long likedUserId) {
        if (likerId == null || likedUserId == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            String docId = getDocId(likerId, likedUserId);
            DocumentSnapshot doc = firestore.collection("user_likes")
                    .document(docId)
                    .get().get();
            if (doc.exists()) {
                return Optional.of(toEntity(doc));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in UserLikeRepository.findByLikerIdAndLikedUserId: " + e.getMessage());
        }
        return Optional.empty();
    }

    public UserLike save(UserLike userLike) {
        if (userLike == null) return null;
        if (userLike.getId() == null) {
            userLike.setId(IdGenerator.generateId());
        }
        operationTracker.trackWrite();
        try {
            String docId = getDocId(userLike.getLiker().getId(), userLike.getLikedUser().getId());
            
            Map<String, Object> data = new HashMap<>();
            data.put("id", userLike.getId());
            data.put("likerId", userLike.getLiker().getId());
            data.put("likedUserId", userLike.getLikedUser().getId());
            data.put("createdAt", com.google.cloud.Timestamp.ofTimeMicroseconds(
                    userLike.getCreatedAt().getEpochSecond() * 1000000L + userLike.getCreatedAt().getNano() / 1000L));

            firestore.collection("user_likes")
                    .document(docId)
                    .set(data).get();
        } catch (Exception e) {
            System.err.println("❌ Error in UserLikeRepository.save: " + e.getMessage());
        }
        return userLike;
    }

    public void delete(UserLike userLike) {
        if (userLike != null && userLike.getLiker() != null && userLike.getLikedUser() != null) {
            operationTracker.trackDelete();
            try {
                String docId = getDocId(userLike.getLiker().getId(), userLike.getLikedUser().getId());
                firestore.collection("user_likes")
                        .document(docId)
                        .delete().get();
            } catch (Exception e) {
                System.err.println("❌ Error in UserLikeRepository.delete: " + e.getMessage());
            }
        }
    }

    public long countByLikedUserId(Long likedUserId) {
        if (likedUserId == null) return 0;
        operationTracker.trackRead();
        try {
            return firestore.collection("user_likes")
                    .whereEqualTo("likedUserId", likedUserId)
                    .count().get().get().getCount();
        } catch (Exception e) {
            System.err.println("❌ Error in UserLikeRepository.countByLikedUserId: " + e.getMessage());
            return 0;
        }
    }

    public boolean existsByLikerIdAndLikedUserId(Long likerId, Long likedUserId) {
        if (likerId == null || likedUserId == null) return false;
        operationTracker.trackRead();
        try {
            String docId = getDocId(likerId, likedUserId);
            return firestore.collection("user_likes")
                    .document(docId)
                    .get().get().exists();
        } catch (Exception e) {
            System.err.println("❌ Error in UserLikeRepository.existsByLikerIdAndLikedUserId: " + e.getMessage());
            return false;
        }
    }

    private UserLike toEntity(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) return null;
        try {
            Long id = doc.getLong("id");
            Long likerId = doc.getLong("likerId");
            Long likedUserId = doc.getLong("likedUserId");
            com.google.cloud.Timestamp createdAtTimestamp = doc.getTimestamp("createdAt");

            User liker = userRepository.findById(likerId).orElse(null);
            User likedUser = userRepository.findById(likedUserId).orElse(null);

            if (liker == null || likedUser == null) {
                liker = liker != null ? liker : User.builder().id(likerId).name("Deleted User").build();
                likedUser = likedUser != null ? likedUser : User.builder().id(likedUserId).name("Deleted User").build();
            }

            return UserLike.builder()
                    .id(id)
                    .liker(liker)
                    .likedUser(likedUser)
                    .createdAt(createdAtTimestamp != null ? createdAtTimestamp.toDate().toInstant() : Instant.now())
                    .build();
        } catch (Exception e) {
            System.err.println("❌ Error mapping UserLike entity: " + e.getMessage());
            return null;
        }
    }
}
