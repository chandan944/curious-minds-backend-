package com.DSA.user;

import lombok.*;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    private Long id;
    private User sender;
    private User receiver; // Null for global chat
    private String content;
    private Instant timestamp;
    private String messageId;

    // ── Reply Feature ──
    private Long replyToId;
    private String replyToContent;
    private String replyToSenderName;

    @Builder.Default
    private String status = "DELIVERED";
}
