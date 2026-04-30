package com.DSA.social;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserLikeRepository extends JpaRepository<UserLike, Long> {

    Optional<UserLike> findByLikerIdAndLikedUserId(Long likerId, Long likedUserId);

    long countByLikedUserId(Long likedUserId);

    boolean existsByLikerIdAndLikedUserId(Long likerId, Long likedUserId);
}
