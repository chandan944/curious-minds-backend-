package com.DSA.social;

import com.DSA.user.User;
import lombok.*;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLike {

    private Long id;
    private User liker;
    private User likedUser;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
