package com.DSA.social;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.Filter;
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
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Repository
public class FriendshipRepository {

    private final Firestore firestore;
    private final UserRepository userRepository;
    private final com.DSA.common.OperationTracker operationTracker;

    // Use @Lazy to prevent any potential circular dependency issues
    public FriendshipRepository(Firestore firestore, @Lazy UserRepository userRepository, com.DSA.common.OperationTracker operationTracker) {
        this.firestore = firestore;
        this.userRepository = userRepository;
        this.operationTracker = operationTracker;
    }

    private String getDocId(Long id1, Long id2) {
        long min = Math.min(id1, id2);
        long max = Math.max(id1, id2);
        return min + "_" + max;
    }

    public Optional<Friendship> findById(Long id) {
        if (id == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            // Check in collection
            List<QueryDocumentSnapshot> docs = firestore.collection("friendships")
                    .whereEqualTo("id", id)
                    .limit(1)
                    .get().get().getDocuments();
            if (!docs.isEmpty()) {
                operationTracker.trackRead();
                return Optional.of(toEntity(docs.get(0)));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in FriendshipRepository.findById: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<Friendship> findBetweenUsers(Long userId1, Long userId2) {
        if (userId1 == null || userId2 == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            String docId = getDocId(userId1, userId2);
            DocumentSnapshot doc = firestore.collection("friendships")
                    .document(docId)
                    .get().get();
            if (doc.exists()) {
                return Optional.of(toEntity(doc));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in FriendshipRepository.findBetweenUsers: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Friendship save(Friendship friendship) {
        if (friendship == null) return null;
        if (friendship.getId() == null) {
            friendship.setId(IdGenerator.generateId());
        }
        operationTracker.trackWrite();
        try {
            String docId = getDocId(friendship.getRequester().getId(), friendship.getAddressee().getId());
            
            Map<String, Object> data = new HashMap<>();
            data.put("id", friendship.getId());
            data.put("requesterId", friendship.getRequester().getId());
            data.put("addresseeId", friendship.getAddressee().getId());
            data.put("status", friendship.getStatus().name());
            data.put("createdAt", com.google.cloud.Timestamp.ofTimeMicroseconds(
                    friendship.getCreatedAt().getEpochSecond() * 1000000L + friendship.getCreatedAt().getNano() / 1000L));

            firestore.collection("friendships")
                    .document(docId)
                    .set(data).get();
        } catch (Exception e) {
            System.err.println("❌ Error in FriendshipRepository.save: " + e.getMessage());
        }
        return friendship;
    }

    public void delete(Friendship friendship) {
        if (friendship != null && friendship.getRequester() != null && friendship.getAddressee() != null) {
            operationTracker.trackDelete();
            try {
                String docId = getDocId(friendship.getRequester().getId(), friendship.getAddressee().getId());
                firestore.collection("friendships")
                        .document(docId)
                        .delete().get();
            } catch (Exception e) {
                System.err.println("❌ Error in FriendshipRepository.delete: " + e.getMessage());
            }
        }
    }

    public long countFriendsByUserId(Long userId) {
        if (userId == null) return 0;
        operationTracker.trackRead();
        try {
            return firestore.collection("friendships")
                    .whereEqualTo("status", "ACCEPTED")
                    .where(Filter.or(
                            Filter.equalTo("requesterId", userId),
                            Filter.equalTo("addresseeId", userId)
                    ))
                    .count().get().get().getCount();
        } catch (Exception e) {
            System.err.println("❌ Error in FriendshipRepository.countFriendsByUserId: " + e.getMessage());
            return 0;
        }
    }

    public Page<Friendship> findAcceptedFriendships(Long userId, Pageable pageable) {
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();
        operationTracker.trackRead(); // Aggregation count query
        try {
            Query query = firestore.collection("friendships")
                    .whereEqualTo("status", "ACCEPTED")
                    .where(Filter.or(
                            Filter.equalTo("requesterId", userId),
                            Filter.equalTo("addresseeId", userId)
                    ));

            long total = query.count().get().get().getCount();
            operationTracker.trackRead(); // Query call
            List<QueryDocumentSnapshot> docs = query.limit(limit).offset(offset).get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            List<Friendship> friendships = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                friendships.add(toEntity(doc));
            }
            return new PageImpl<>(friendships, pageable, total);
        } catch (Exception e) {
            System.err.println("❌ Error in FriendshipRepository.findAcceptedFriendships: " + e.getMessage());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    public List<Friendship> findPendingRequestsForUser(Long userId) {
        List<Friendship> pending = new ArrayList<>();
        if (userId == null) return pending;
        operationTracker.trackRead(); // Query call
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("friendships")
                    .whereEqualTo("status", "PENDING")
                    .whereEqualTo("addresseeId", userId)
                    .get().get().getDocuments();
            operationTracker.trackReads(docs.size());
            for (QueryDocumentSnapshot doc : docs) {
                pending.add(toEntity(doc));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in FriendshipRepository.findPendingRequestsForUser: " + e.getMessage());
        }
        return pending;
    }

    /**
     * Optimized in-memory graph traversal for mutual friends recommendations.
     * Replaces complex SQL recursive CTE.
     */
    public List<Object[]> findMutualFriendRecommendations(Long userId, int limit, int offset) {
        try {
            // 1. Get user's direct accepted friends
            operationTracker.trackRead(); // Query call
            List<QueryDocumentSnapshot> directFriendDocs = firestore.collection("friendships")
                    .whereEqualTo("status", "ACCEPTED")
                    .where(Filter.or(
                            Filter.equalTo("requesterId", userId),
                            Filter.equalTo("addresseeId", userId)
                    ))
                    .get().get().getDocuments();

            operationTracker.trackReads(directFriendDocs.size());
            Set<Long> directFriends = new HashSet<>();
            for (QueryDocumentSnapshot doc : directFriendDocs) {
                Long rId = doc.getLong("requesterId");
                Long aId = doc.getLong("addresseeId");
                if (rId != null && aId != null) {
                    directFriends.add(rId.equals(userId) ? aId : rId);
                }
            }

            if (directFriends.isEmpty()) {
                return Collections.emptyList();
            }

            // 2. Fetch friendships of direct friends to find friends-of-friends (Degree 2)
            Map<Long, Set<Long>> mutualMap = new HashMap<>(); // potentialFriendId -> Set of mutual friend IDs

            for (Long friendId : directFriends) {
                operationTracker.trackRead(); // Query call for this friend's friends
                List<QueryDocumentSnapshot> fDocs = firestore.collection("friendships")
                        .whereEqualTo("status", "ACCEPTED")
                        .where(Filter.or(
                                Filter.equalTo("requesterId", friendId),
                                Filter.equalTo("addresseeId", friendId)
                        ))
                        .get().get().getDocuments();

                operationTracker.trackReads(fDocs.size());
                for (QueryDocumentSnapshot doc : fDocs) {
                    Long rId = doc.getLong("requesterId");
                    Long aId = doc.getLong("addresseeId");
                    if (rId != null && aId != null) {
                        Long fofId = rId.equals(friendId) ? aId : rId;
                        // Skip self and direct friends
                        if (!fofId.equals(userId) && !directFriends.contains(fofId)) {
                            mutualMap.computeIfAbsent(fofId, k -> new HashSet<>()).add(friendId);
                        }
                    }
                }
            }

            // 3. Convert to Object[] rows: [friend_id, mutual_count, degree]
            List<Object[]> recommendations = new ArrayList<>();
            for (Map.Entry<Long, Set<Long>> entry : mutualMap.entrySet()) {
                Long fofId = entry.getKey();
                long mutualCount = entry.getValue().size();
                recommendations.add(new Object[]{fofId, mutualCount, 2}); // All are 2nd degree
            }

            // 4. Sort: degree ASC, mutual_count DESC
            recommendations.sort((r1, r2) -> {
                int deg1 = (Integer) r1[2];
                int deg2 = (Integer) r2[2];
                if (deg1 != deg2) {
                    return Integer.compare(deg1, deg2);
                }
                long count1 = (Long) r1[1];
                long count2 = (Long) r2[1];
                return Long.compare(count2, count1); // DESC
            });

            // 5. Apply Pagination (limit/offset)
            int total = recommendations.size();
            if (offset >= total) {
                return Collections.emptyList();
            }
            int end = Math.min(offset + limit, total);
            return recommendations.subList(offset, end);

        } catch (Exception e) {
            System.err.println("❌ Error in FriendshipRepository.findMutualFriendRecommendations: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private Friendship toEntity(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) return null;
        try {
            Long id = doc.getLong("id");
            Long requesterId = doc.getLong("requesterId");
            Long addresseeId = doc.getLong("addresseeId");
            String statusStr = doc.getString("status");
            com.google.cloud.Timestamp createdAtTimestamp = doc.getTimestamp("createdAt");

            User requester = userRepository.findById(requesterId).orElse(null);
            User addressee = userRepository.findById(addresseeId).orElse(null);

            if (requester == null || addressee == null) {
                // If users don't exist in DB anymore, return partial
                requester = requester != null ? requester : User.builder().id(requesterId).name("Deleted User").build();
                addressee = addressee != null ? addressee : User.builder().id(addresseeId).name("Deleted User").build();
            }

            return Friendship.builder()
                    .id(id)
                    .requester(requester)
                    .addressee(addressee)
                    .status(FriendshipStatus.valueOf(statusStr))
                    .createdAt(createdAtTimestamp != null ? createdAtTimestamp.toDate().toInstant() : Instant.now())
                    .build();
        } catch (Exception e) {
            System.err.println("❌ Error mapping Friendship entity: " + e.getMessage());
            return null;
        }
    }
}
