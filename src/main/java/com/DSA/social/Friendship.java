package com.DSA.social;

import com.DSA.user.User;
import lombok.*;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Friendship {

    private Long id;
    private User requester;
    private User addressee;
    private FriendshipStatus status;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
