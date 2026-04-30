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
}
