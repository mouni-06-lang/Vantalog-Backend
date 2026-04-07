package com.vantalog.backend.controller;

import com.vantalog.backend.security.AuthenticatedUser;
import com.vantalog.backend.security.JwtService;
import com.vantalog.backend.util.ApiException;
import com.vantalog.backend.util.ResponseMappers;
import com.vantalog.backend.util.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(NamedParameterJdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/precheck-login")
    public Map<String, Object> precheckLogin(@RequestBody Map<String, Object> body) {
        String email = requiredString(body, "email").toLowerCase();
        String password = requiredString(body, "password");
        String role = stringValue(body.get("role"));

        Map<String, Object> user = loadUserByEmail(email);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Entered unregistered email.");
        }
        if (!ResponseMappers.boolValue(user.get("active"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account is inactive. Please contact admin.");
        }
        if (role != null && !role.equals(user.get("role"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Entered wrong email.");
        }
        if (!passwordEncoder.matches(password, ResponseMappers.stringValue(user.get("password_hash")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Entered wrong password.");
        }
        return Map.of("message", "Credentials verified.");
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String email = requiredString(body, "email").toLowerCase();
        String password = requiredString(body, "password");
        String role = stringValue(body.get("role"));

        Map<String, Object> user = loadUserByEmail(email);
        if (user == null || !ResponseMappers.boolValue(user.get("active"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        if (role != null && !role.equals(user.get("role"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account does not have " + role + " access.");
        }
        if (!passwordEncoder.matches(password, ResponseMappers.stringValue(user.get("password_hash")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        AuthenticatedUser publicUser = ResponseMappers.publicUser(user);
        return Map.of(
                "token", jwtService.generateToken(publicUser),
                "user", ResponseMappers.publicUserMap(user)
        );
    }

    @PostMapping("/register")
    public Map<String, Object> register() {
        throw new ApiException(HttpStatus.FORBIDDEN, "Public signup is disabled. Please contact an admin to create your account.");
    }

    @PostMapping("/admin-request")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> adminRequest(@RequestBody Map<String, Object> body) {
        String fullName = requiredString(body, "fullName");
        String displayName = requiredString(body, "displayName");
        String email = requiredString(body, "email").toLowerCase();
        String password = requiredString(body, "password");

        Integer existingRequest = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_requests WHERE email = :email",
                new MapSqlParameterSource("email", email),
                Integer.class
        );
        if (existingRequest != null && existingRequest > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "An admin request already exists for this email.");
        }

        Integer existingUser = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = :email",
                new MapSqlParameterSource("email", email),
                Integer.class
        );
        if (existingUser != null && existingUser > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This email is already registered. Ask an admin to update your role.");
        }

        jdbcTemplate.update("""
                INSERT INTO admin_requests (id, full_name, display_name, email, password_hash, status)
                VALUES (:id, :fullName, :displayName, :email, :passwordHash, 'pending')
                """, new MapSqlParameterSource(Map.of(
                "id", UUID.randomUUID().toString(),
                "fullName", fullName.trim(),
                "displayName", displayName.trim(),
                "email", email,
                "passwordHash", passwordEncoder.encode(password)
        )));

        return Map.of("message", "Admin access request submitted successfully.");
    }

    @PostMapping("/verify-code")
    public Map<String, Object> verifyCode(@RequestBody Map<String, Object> body) {
        String code = stringValue(body.get("code"));
        if (code == null || code.length() != 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A valid 6-digit code is required.");
        }
        return Map.of("valid", true);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Map.of("message", "Logged out successfully.");
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        AuthenticatedUser user = SecurityUtils.requireUser();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, email, role, bio, avatar_url, active, created_at FROM users WHERE id = :id LIMIT 1",
                new MapSqlParameterSource("id", user.id())
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "User not found or inactive.");
        }
        return ResponseMappers.publicUserMap(rows.getFirst());
    }

    @PutMapping("/password")
    public Map<String, Object> updatePassword(@RequestBody Map<String, Object> body) {
        AuthenticatedUser user = SecurityUtils.requireUser();
        String currentPassword = requiredString(body, "currentPassword");
        String newPassword = requiredString(body, "newPassword");
        Map<String, Object> dbUser = loadUserById(user.id());
        if (!passwordEncoder.matches(currentPassword, ResponseMappers.stringValue(dbUser.get("password_hash")))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        jdbcTemplate.update("UPDATE users SET password_hash = :passwordHash WHERE id = :id",
                new MapSqlParameterSource(Map.of("id", user.id(), "passwordHash", passwordEncoder.encode(newPassword))));
        return Map.of("message", "Password updated successfully.");
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody Map<String, Object> body) {
        String fullName = stringValue(body.get("fullName"));
        String email = requiredString(body, "email").toLowerCase();
        String previousPassword = stringValue(body.get("previousPassword"));
        String newPassword = requiredString(body, "newPassword");
        String role = stringValue(body.get("role"));

        Map<String, Object> user = loadUserByEmail(email);
        if (user == null || (role != null && !role.equals(user.get("role")))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No matching account found.");
        }

        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO password_reset_requests (id, user_id, full_name, email, role, previous_password_provided, status)
                VALUES (:id, :userId, :fullName, :email, :role, :previousPasswordProvided, 'pending')
                """, new MapSqlParameterSource()
                .addValue("id", requestId)
                .addValue("userId", user.get("id"))
                .addValue("fullName", fullName == null || fullName.isBlank() ? user.get("name") : fullName)
                .addValue("email", email)
                .addValue("role", role == null ? user.get("role") : role)
                .addValue("previousPasswordProvided", previousPassword != null && !previousPassword.isBlank() ? 1 : 0));

        if (fullName != null && !fullName.isBlank() && !fullName.trim().equalsIgnoreCase(ResponseMappers.stringValue(user.get("name")))) {
            jdbcTemplate.update("UPDATE password_reset_requests SET status = 'failed' WHERE id = :id", new MapSqlParameterSource("id", requestId));
            throw new ApiException(HttpStatus.BAD_REQUEST, "Full name does not match this account.");
        }
        if (previousPassword != null && !previousPassword.isBlank() && !passwordEncoder.matches(previousPassword, ResponseMappers.stringValue(user.get("password_hash")))) {
            jdbcTemplate.update("UPDATE password_reset_requests SET status = 'failed' WHERE id = :id", new MapSqlParameterSource("id", requestId));
            throw new ApiException(HttpStatus.BAD_REQUEST, "Previous password is incorrect.");
        }

        jdbcTemplate.update("UPDATE users SET password_hash = :passwordHash WHERE id = :id",
                new MapSqlParameterSource(Map.of("id", user.get("id"), "passwordHash", passwordEncoder.encode(newPassword))));
        jdbcTemplate.update("UPDATE password_reset_requests SET status = 'completed' WHERE id = :id", new MapSqlParameterSource("id", requestId));
        return Map.of("message", "Password reset successfully. You can now log in with your new password.");
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public Map<String, Object> resetPassword() {
        return Map.of("message", "Token-based password reset is not enabled in this project build.");
    }

    private Map<String, Object> loadUserByEmail(String email) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM users WHERE email = :email LIMIT 1",
                new MapSqlParameterSource("email", email));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> loadUserById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM users WHERE id = :id LIMIT 1",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found.");
        }
        return rows.getFirst();
    }

    private String requiredString(Map<String, Object> body, String key) {
        String value = stringValue(body.get(key));
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " is required.");
        }
        return value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
