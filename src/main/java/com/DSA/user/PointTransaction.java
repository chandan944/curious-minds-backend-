package com.DSA.user;

import lombok.*;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointTransaction {

    private Long id;
    private User user;
    private int amount;
    private Instant earnedAt;
    private String reason; // e.g., "quiz_correct", "theory_read", "streak_bonus"
}
