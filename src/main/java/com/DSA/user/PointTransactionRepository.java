package com.DSA.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    // Sum of points per user within a time window, ordered by total descending
    @Query(value = """
        SELECT pt.user.id as userId,
               pt.user.name as name,
               pt.user.imageUrl as imageUrl,
               pt.user.level as level,
               pt.user.title as title,
               SUM(pt.amount) as totalPoints
        FROM PointTransaction pt
        WHERE pt.earnedAt >= :from
        GROUP BY pt.user.id, pt.user.name, pt.user.imageUrl, pt.user.level, pt.user.title
        ORDER BY SUM(pt.amount) DESC
    """, countQuery = """
        SELECT COUNT(DISTINCT pt.user.id)
        FROM PointTransaction pt
        WHERE pt.earnedAt >= :from
    """)
    Page<Object[]> findTopUsersByPointsSince(@Param("from") LocalDateTime from, Pageable pageable);

    @Query("""
        SELECT SUM(pt.amount)
        FROM PointTransaction pt
        WHERE pt.user.id = :userId
    """)
    Integer getTotalPointsForUser(@Param("userId") Long userId);
}
