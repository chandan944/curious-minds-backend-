package com.DSA.social;

import com.DSA.user.User;
import lombok.*;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    private Long id;
    private User recipient;
    private NotificationType type;
    private Long senderId;
    private String senderName;
    private String senderImage;
    private String message;

    @Builder.Default
    private boolean isRead = false;

    @Builder.Default
    private Instant createdAt = Instant.now();

    public String getIdString() {
        return id != null ? String.valueOf(id) : null;
    }

    public String getSenderIdString() {
        return senderId != null ? String.valueOf(senderId) : null;
    }
}
