package com.DSA.user;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "receiver_id")
    private User receiver; // Null for global chat

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    // ── Reply Feature ──
    @Column(name = "reply_to_id")
    private Long replyToId;

    @Column(name = "reply_to_content", length = 500)
    private String replyToContent;

    @Column(name = "reply_to_sender_name")
    private String replyToSenderName;

    @Builder.Default
    @Column(nullable = false)
    private String status = "DELIVERED";
}
