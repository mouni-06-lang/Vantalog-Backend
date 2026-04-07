package com.vantalog.backend.service;

import com.vantalog.backend.config.AppProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class StartupService implements CommandLineRunner {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;
    private final PasswordEncoder passwordEncoder;

    public StartupService(NamedParameterJdbcTemplate jdbcTemplate, AppProperties appProperties, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.appProperties = appProperties;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!appProperties.seedDefaultAdmin()) {
            return;
        }
        if (isBlank(appProperties.defaultAdminName()) || isBlank(appProperties.defaultAdminEmail()) || isBlank(appProperties.defaultAdminPassword())) {
            return;
        }
        Integer admins = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'admin'", new MapSqlParameterSource(), Integer.class);
        if (admins != null && admins == 0) {
            jdbcTemplate.update("""
                    INSERT INTO users (id, name, email, password_hash, role, active)
                    VALUES (:id, :name, :email, :passwordHash, 'admin', 1)
                    """, new MapSqlParameterSource(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "name", appProperties.defaultAdminName(),
                    "email", appProperties.defaultAdminEmail().toLowerCase(),
                    "passwordHash", passwordEncoder.encode(appProperties.defaultAdminPassword())
            )));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
