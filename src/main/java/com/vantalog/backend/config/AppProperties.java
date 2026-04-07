package com.vantalog.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendUrl,
        String jwtSecret,
        long jwtExpirationMs,
        boolean seedDefaultAdmin,
        String defaultAdminName,
        String defaultAdminEmail,
        String defaultAdminPassword,
        String uploadDir
) {
}
