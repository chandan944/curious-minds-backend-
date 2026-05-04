package com.DSA.social;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    // Find an existing friendship in either direction
    @Query("SELECT f FROM Friendship f WHERE " +
           "(f.requester.id = :userId1 AND f.addressee.id = :userId2) OR " +
           "(f.requester.id = :userId2 AND f.addressee.id = :userId1)")
    Optional<Friendship> findBetweenUsers(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    // Count accepted friends for a user (in either direction)
    @Query("SELECT COUNT(f) FROM Friendship f WHERE f.status = 'ACCEPTED' AND " +
           "(f.requester.id = :userId OR f.addressee.id = :userId)")
    long countFriendsByUserId(@Param("userId") Long userId);

    // Find all accepted friendships for a user
    @Query("SELECT f FROM Friendship f WHERE f.status = 'ACCEPTED' AND " +
           "(f.requester.id = :userId OR f.addressee.id = :userId)")
    List<Friendship> findAcceptedFriendships(@Param("userId") Long userId);

    // Find pending requests received by user
    @Query("SELECT f FROM Friendship f WHERE f.status = 'PENDING' AND f.addressee.id = :userId")
    List<Friendship> findPendingRequestsForUser(@Param("userId") Long userId);

    // Mutual Friend Recommendations (Native Query for performance)
    @Query(value = "WITH my_friends AS (" +
           "    SELECT CASE WHEN requester_id = :userId THEN addressee_id ELSE requester_id END as friend_id " +
           "    FROM friendships " +
           "    WHERE (requester_id = :userId OR addressee_id = :userId) AND status = 'ACCEPTED'" +
           "), " +
           "friends_of_friends AS (" +
           "    SELECT CASE WHEN f.requester_id = mf.friend_id THEN f.addressee_id ELSE f.requester_id END as potential_friend_id, " +
           "           mf.friend_id as mutual_friend_id " +
           "    FROM friendships f " +
           "    JOIN my_friends mf ON (f.requester_id = mf.friend_id OR f.addressee_id = mf.friend_id) " +
           "    WHERE f.status = 'ACCEPTED'" +
           ") " +
           "SELECT potential_friend_id, COUNT(mutual_friend_id) as mutual_count " +
           "FROM friends_of_friends " +
           "WHERE potential_friend_id != :userId " +
           "  AND potential_friend_id NOT IN (SELECT friend_id FROM my_friends) " +
           "GROUP BY potential_friend_id " +
           "ORDER BY mutual_count DESC " +
           "LIMIT :limit", nativeQuery = true)
    List<Object[]> findMutualFriendRecommendations(@Param("userId") Long userId, @Param("limit") int limit);
}
