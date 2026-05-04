package com.DSA.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // Global Chat history
    Page<ChatMessage> findByReceiverIsNullOrderByTimestampDesc(Pageable pageable);

    // Direct message history between two users
    @Query("SELECT m FROM ChatMessage m WHERE (m.sender.id = :user1 AND m.receiver.id = :user2) OR (m.sender.id = :user2 AND m.receiver.id = :user1) ORDER BY m.timestamp DESC")
    Page<ChatMessage> findDirectMessages(@Param("user1") Long user1, @Param("user2") Long user2, Pageable pageable);

    // Find users the current user has chatted with recently
    @Query("""
        SELECT DISTINCT u FROM User u
        WHERE u.id IN (
            SELECT m.receiver.id FROM ChatMessage m WHERE m.sender.id = :userId AND m.receiver IS NOT NULL
        ) OR u.id IN (
            SELECT m.sender.id FROM ChatMessage m WHERE m.receiver.id = :userId
        )
    """)
    List<User> findRecentChatPartners(@Param("userId") Long userId);

    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.receiver.id = :userId AND m.status != 'READ'")
    int countUnreadMessages(@Param("userId") Long userId);
}
