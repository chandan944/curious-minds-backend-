package com.DSA.ebook;

import lombok.*;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ebook {

    private Long id;
    private String title;
    private String englishFileUrl;
    private String hindiFileUrl;
    private String description;
    private String coverImageUrl;
    private String uploaderEmail;
    private Instant uploadedAt;

    // Zero-dependency helper to prevent JavaScript integer precision loss
    public String getIdString() {
        return id != null ? String.valueOf(id) : null;
    }
}
