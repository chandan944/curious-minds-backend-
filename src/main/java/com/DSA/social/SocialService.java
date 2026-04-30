package com.DSA.social;

import com.DSA.config.ChatWebSocketHandler;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SocialService {

    private final UserLikeRepository userLikeRepository;
    private final FriendshipRepository friendshipRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ChatWebSocketHandler chatWebSocketHandler;

    // ── Like / Unlike ─────────────────────────────────────────────────────────
    @Transactional
    public boolean toggleLike(Long likerId, Long targetUserId) {
        if (likerId.equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot like yourself");
        }

        Optional<UserLike> existing = userLikeRepository.findByLikerIdAndLikedUserId(likerId, targetUserId);

        if (existing.isPresent()) {
            // Unlike
            userLikeRepository.delete(existing.get());
            return false; // now unliked
        }

        // Like
        User liker = userRepository.findById(likerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + likerId));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + targetUserId));

        UserLike like = UserLike.builder()
                .liker(liker)
                .likedUser(target)
                .build();
        userLikeRepository.save(like);

        // Create and push notification
        Notification notif = Notification.builder()
                .recipient(target)
                .type(NotificationType.LIKE)
                .senderId(likerId)
                .senderName(liker.getName())
                .senderImage(liker.getImageUrl())
                .message(liker.getName() + " liked your profile")
                .build();
        notificationRepository.save(notif);

        pushNotification(notif);
        return true; // now liked
    }

    // ── Friend Request ────────────────────────────────────────────────────────
    @Transactional
    public String sendFriendRequest(Long requesterId, Long addresseeId) {
        if (requesterId.equals(addresseeId)) {
            throw new IllegalArgumentException("Cannot friend yourself");
        }

        Optional<Friendship> existing = friendshipRepository.findBetweenUsers(requesterId, addresseeId);
        if (existing.isPresent()) {
            FriendshipStatus status = existing.get().getStatus();
            if (status == FriendshipStatus.ACCEPTED) {
                return "ALREADY_FRIENDS";
            }
            return "ALREADY_PENDING";
        }

        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + requesterId));
        User addressee = userRepository.findById(addresseeId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + addresseeId));

        Friendship friendship = Friendship.builder()
                .requester(requester)
                .addressee(addressee)
                .status(FriendshipStatus.PENDING)
                .build();
        friendshipRepository.save(friendship);

        // Create and push notification
        Notification notif = Notification.builder()
                .recipient(addressee)
                .type(NotificationType.FRIEND_REQUEST)
                .senderId(requesterId)
                .senderName(requester.getName())
                .senderImage(requester.getImageUrl())
                .message(requester.getName() + " sent you a friend request")
                .build();
        notificationRepository.save(notif);

        pushNotification(notif);
        return "REQUEST_SENT";
    }

    // ── Accept Friend Request ─────────────────────────────────────────────────
    @Transactional
    public String acceptFriendRequest(Long currentUserId, Long requesterId) {
        Optional<Friendship> existing = friendshipRepository.findBetweenUsers(requesterId, currentUserId);

        if (existing.isEmpty()) {
            return "NO_REQUEST";
        }

        Friendship friendship = existing.get();
        if (friendship.getStatus() == FriendshipStatus.ACCEPTED) {
            return "ALREADY_FRIENDS";
        }

        // Only the addressee can accept
        if (!friendship.getAddressee().getId().equals(currentUserId)) {
            return "NOT_AUTHORIZED";
        }

        friendship.setStatus(FriendshipStatus.ACCEPTED);
        friendshipRepository.save(friendship);

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Notify the requester that their request was accepted
        Notification notif = Notification.builder()
                .recipient(friendship.getRequester())
                .type(NotificationType.FRIEND_ACCEPT)
                .senderId(currentUserId)
                .senderName(currentUser.getName())
                .senderImage(currentUser.getImageUrl())
                .message(currentUser.getName() + " accepted your friend request")
                .build();
        notificationRepository.save(notif);

        pushNotification(notif);
        return "ACCEPTED";
    }

    // ── Get social stats for a profile ────────────────────────────────────────
    public long getLikeCount(Long userId) {
        return userLikeRepository.countByLikedUserId(userId);
    }

    public long getFriendCount(Long userId) {
        return friendshipRepository.countFriendsByUserId(userId);
    }

    public boolean hasLiked(Long likerId, Long targetId) {
        return userLikeRepository.existsByLikerIdAndLikedUserId(likerId, targetId);
    }

    public String getFriendshipStatus(Long userId1, Long userId2) {
        Optional<Friendship> f = friendshipRepository.findBetweenUsers(userId1, userId2);
        if (f.isEmpty()) return "NONE";
        return f.get().getStatus().name();
    }

    // ── Push notification via WebSocket ────────────────────────────────────────
    private void pushNotification(Notification notif) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "NOTIFICATION");
            payload.addProperty("id", notif.getId());
            payload.addProperty("notifType", notif.getType().name());
            payload.addProperty("senderId", notif.getSenderId());
            payload.addProperty("senderName", notif.getSenderName());
            payload.addProperty("senderImage", notif.getSenderImage() != null ? notif.getSenderImage() : "");
            payload.addProperty("message", notif.getMessage());
            payload.addProperty("isRead", notif.isRead());
            payload.addProperty("createdAt", notif.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            chatWebSocketHandler.sendToUser(notif.getRecipient().getId(), payload);
        } catch (Exception e) {
            System.err.println("⚠️ Failed to push notification: " + e.getMessage());
        }
    }
}
