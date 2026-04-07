package com.vantalog.backend.security;

import java.time.LocalDateTime;

public record AuthenticatedUser(
        String id,
        String name,
        String email,
        String role,
        String bio,
        String avatarUrl,
        boolean active,
        LocalDateTime createdAt
) {
}
