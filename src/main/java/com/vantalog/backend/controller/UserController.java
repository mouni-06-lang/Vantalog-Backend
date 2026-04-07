package com.vantalog.backend.controller;

import com.vantalog.backend.security.AuthenticatedUser;
import com.vantalog.backend.service.FileStorageService;
import com.vantalog.backend.util.ApiException;
import com.vantalog.backend.util.ResponseMappers;
import com.vantalog.backend.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;

    public UserController(NamedParameterJdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, FileStorageService fileStorageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        AuthenticatedUser user = SecurityUtils.requireUser();
        Integer downloads = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM resource_access_logs
                WHERE user_id = :userId AND access_type = 'download'
                """, new MapSqlParameterSource("userId", user.id()), Integer.class);
        Integer favorites = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM favorites WHERE user_id = :userId",
                new MapSqlParameterSource("userId", user.id()), Integer.class);
        Integer uploads = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM resources WHERE uploaded_by = :userId AND active = 1",
                new MapSqlParameterSource("userId", user.id()), Integer.class);
        return Map.of("downloads", downloads == null ? 0 : downloads, "favorites", favorites == null ? 0 : favorites, "uploads", uploads == null ? 0 : uploads);
    }

    @GetMapping("/downloads")
    public Map<String, Object> downloads() {
        AuthenticatedUser user = SecurityUtils.requireUser();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.*
                FROM resource_access_logs ral
                INNER JOIN resources r ON r.id = ral.resource_id
                WHERE ral.user_id = :userId AND ral.access_type = 'download' AND r.active = 1
                GROUP BY r.id
                ORDER BY MAX(ral.created_at) DESC
                """, new MapSqlParameterSource("userId", user.id()));
        return Map.of("resources", rows.stream().map(ResponseMappers::normalizeResource).toList());
    }

    @GetMapping("/favorites")
    public Map<String, Object> favorites() {
        AuthenticatedUser user = SecurityUtils.requireUser();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.*, f.created_at AS favorited_at
                FROM favorites f
                INNER JOIN resources r ON r.id = f.resource_id
                WHERE f.user_id = :userId AND r.active = 1
                ORDER BY f.created_at DESC
                """, new MapSqlParameterSource("userId", user.id()));
        return Map.of("resources", rows.stream().map(row -> {
            Map<String, Object> resource = new LinkedHashMap<>(ResponseMappers.normalizeResource(row));
            resource.put("favoritedAt", row.get("favorited_at"));
            return resource;
        }).toList());
    }

    @PostMapping("/favorites/{resourceId}")
    public Map<String, Object> addFavorite(@PathVariable String resourceId) {
        AuthenticatedUser user = SecurityUtils.requireUser();
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM favorites WHERE user_id = :userId AND resource_id = :resourceId
                """, new MapSqlParameterSource().addValue("userId", user.id()).addValue("resourceId", resourceId), Integer.class);
        if (existing == null || existing == 0) {
            jdbcTemplate.update("""
                    INSERT INTO favorites (id, user_id, resource_id) VALUES (:id, :userId, :resourceId)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString())
                    .addValue("userId", user.id())
                    .addValue("resourceId", resourceId));
        }
        return Map.of("message", "Resource added to favorites.");
    }

    @DeleteMapping("/favorites/{resourceId}")
    public Map<String, Object> removeFavorite(@PathVariable String resourceId) {
        AuthenticatedUser user = SecurityUtils.requireUser();
        jdbcTemplate.update("DELETE FROM favorites WHERE user_id = :userId AND resource_id = :resourceId",
                new MapSqlParameterSource().addValue("userId", user.id()).addValue("resourceId", resourceId));
        return Map.of("message", "Resource removed from favorites.");
    }

    @GetMapping("/{userId}/profile")
    public Map<String, Object> profile(@PathVariable String userId) {
        AuthenticatedUser currentUser = SecurityUtils.requireUser();
        String targetId = "me".equals(userId) ? currentUser.id() : userId;
        if (!targetId.equals(currentUser.id()) && !"admin".equals(currentUser.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot view this profile.");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, name, email, role, bio, avatar_url, active, created_at
                FROM users WHERE id = :id LIMIT 1
                """, new MapSqlParameterSource("id", targetId));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found.");
        }
        return ResponseMappers.publicUserMap(rows.getFirst());
    }

    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody Map<String, Object> body) {
        AuthenticatedUser currentUser = SecurityUtils.requireUser();
        String name = required(body, "name");
        String email = required(body, "email").toLowerCase();
        String bio = stringValue(body.get("bio"));
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM users WHERE email = :email AND id <> :id
                """, new MapSqlParameterSource().addValue("email", email).addValue("id", currentUser.id()), Integer.class);
        if (existing != null && existing > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Another account already uses this email.");
        }
        jdbcTemplate.update("""
                UPDATE users SET name = :name, email = :email, bio = :bio WHERE id = :id
                """, new MapSqlParameterSource().addValue("name", name.trim()).addValue("email", email).addValue("bio", bio).addValue("id", currentUser.id()));
        return ResponseMappers.publicUserMap(loadUser(currentUser.id()));
    }

    @PostMapping("/avatar")
    public Map<String, Object> uploadAvatar(@RequestParam MultipartFile avatar) throws IOException {
        AuthenticatedUser currentUser = SecurityUtils.requireUser();
        if (avatar == null || avatar.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Avatar file is required.");
        }
        String storedName = fileStorageService.store(avatar);
        String avatarUrl = "/uploads/" + storedName;
        jdbcTemplate.update("UPDATE users SET avatar_url = :avatarUrl WHERE id = :id",
                new MapSqlParameterSource().addValue("avatarUrl", avatarUrl).addValue("id", currentUser.id()));
        return Map.of("avatarUrl", avatarUrl);
    }

    @GetMapping
    public Map<String, Object> allUsers(@RequestParam(required = false) String role,
                                        @RequestParam(required = false) String search) {
        SecurityUtils.requireAdmin();
        StringBuilder where = new StringBuilder("1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (StringUtils.hasText(role)) {
            where.append(" AND role = :role");
            params.addValue("role", role);
        }
        if (StringUtils.hasText(search)) {
            where.append(" AND (name LIKE :search OR email LIKE :search)");
            params.addValue("search", "%" + search + "%");
        }
        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                SELECT id, name, email, role, bio, avatar_url, active, created_at
                FROM users WHERE %s ORDER BY created_at DESC
                """.formatted(where), params);
        List<Map<String, Object>> adminRequests = jdbcTemplate.queryForList("""
                SELECT id, full_name, display_name, email, status, created_at
                FROM admin_requests ORDER BY created_at DESC
                """, new MapSqlParameterSource());
        return Map.of(
                "users", users.stream().map(ResponseMappers::publicUserMap).toList(),
                "adminRequests", adminRequests.stream().map(row -> Map.<String, Object>of(
                        "id", row.get("id"),
                        "fullName", row.get("full_name"),
                        "displayName", row.get("display_name"),
                        "email", row.get("email"),
                        "status", row.get("status"),
                        "createdAt", row.get("created_at")
                )).toList()
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        SecurityUtils.requireAdmin();
        String name = required(body, "name");
        String email = required(body, "email").toLowerCase();
        String password = required(body, "password");
        String role = "admin".equals(stringValue(body.get("role"))) ? "admin" : "user";
        String bio = stringValue(body.get("bio"));
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = :email",
                new MapSqlParameterSource("email", email), Integer.class);
        if (existing != null && existing > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        jdbcTemplate.update("""
                INSERT INTO users (id, name, email, password_hash, role, bio, active)
                VALUES (:id, :name, :email, :passwordHash, :role, :bio, 1)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString())
                .addValue("name", name.trim())
                .addValue("email", email)
                .addValue("passwordHash", passwordEncoder.encode(password))
                .addValue("role", role)
                .addValue("bio", bio));
        return Map.of("message", ("admin".equals(role) ? "Admin" : "User") + " created successfully.");
    }

    @PostMapping("/admin-requests/{requestId}/approve")
    public Map<String, Object> approveAdminRequest(@PathVariable String requestId) {
        AuthenticatedUser admin = SecurityUtils.requireAdmin();
        List<Map<String, Object>> requests = jdbcTemplate.queryForList("SELECT * FROM admin_requests WHERE id = :id LIMIT 1",
                new MapSqlParameterSource("id", requestId));
        if (requests.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Admin request not found.");
        }
        Map<String, Object> request = requests.getFirst();
        if ("approved".equals(request.get("status"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This admin request is already approved.");
        }
        List<Map<String, Object>> users = jdbcTemplate.queryForList("SELECT id FROM users WHERE email = :email LIMIT 1",
                new MapSqlParameterSource("email", request.get("email")));
        if (users.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO users (id, name, email, password_hash, role, active)
                    VALUES (:id, :name, :email, :passwordHash, 'admin', 1)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString())
                    .addValue("name", request.get("display_name") == null ? request.get("full_name") : request.get("display_name"))
                    .addValue("email", request.get("email"))
                    .addValue("passwordHash", request.get("password_hash")));
        } else {
            jdbcTemplate.update("UPDATE users SET role = 'admin', active = 1 WHERE id = :id",
                    new MapSqlParameterSource("id", users.getFirst().get("id")));
        }
        jdbcTemplate.update("""
                UPDATE admin_requests SET status = 'approved', reviewed_by = :reviewedBy, reviewed_at = CURRENT_TIMESTAMP WHERE id = :id
                """, new MapSqlParameterSource().addValue("reviewedBy", admin.id()).addValue("id", requestId));
        return Map.of("message", "Admin request approved successfully.");
    }

    @PostMapping("/admin-requests/{requestId}/reject")
    public Map<String, Object> rejectAdminRequest(@PathVariable String requestId) {
        AuthenticatedUser admin = SecurityUtils.requireAdmin();
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_requests WHERE id = :id",
                new MapSqlParameterSource("id", requestId), Integer.class);
        if (existing == null || existing == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Admin request not found.");
        }
        jdbcTemplate.update("""
                UPDATE admin_requests SET status = 'rejected', reviewed_by = :reviewedBy, reviewed_at = CURRENT_TIMESTAMP WHERE id = :id
                """, new MapSqlParameterSource().addValue("reviewedBy", admin.id()).addValue("id", requestId));
        return Map.of("message", "Admin request rejected.");
    }

    @PutMapping("/{userId}/role")
    public Map<String, Object> updateRole(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        SecurityUtils.requireAdmin();
        jdbcTemplate.update("UPDATE users SET role = :role WHERE id = :id",
                new MapSqlParameterSource().addValue("role", body.get("role")).addValue("id", userId));
        return Map.of("message", "User role updated successfully.");
    }

    @PutMapping("/{userId}")
    public Map<String, Object> updateUser(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        SecurityUtils.requireAdmin();
        String email = required(body, "email").toLowerCase();
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM users WHERE email = :email AND id <> :id
                """, new MapSqlParameterSource().addValue("email", email).addValue("id", userId), Integer.class);
        if (existing != null && existing > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Another account already uses this email.");
        }
        jdbcTemplate.update("""
                UPDATE users SET name = :name, email = :email, role = :role, bio = :bio, active = :active WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("name", required(body, "name").trim())
                .addValue("email", email)
                .addValue("role", body.get("role"))
                .addValue("bio", stringValue(body.get("bio")))
                .addValue("active", Boolean.parseBoolean(String.valueOf(body.get("active"))) ? 1 : 0));
        return Map.of("message", "User updated successfully.");
    }

    @PutMapping("/{userId}/status")
    public Map<String, Object> updateStatus(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        SecurityUtils.requireAdmin();
        jdbcTemplate.update("UPDATE users SET active = :active WHERE id = :id",
                new MapSqlParameterSource().addValue("id", userId).addValue("active", Boolean.parseBoolean(String.valueOf(body.get("active"))) ? 1 : 0));
        return Map.of("message", "User status updated successfully.");
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> delete(@PathVariable String userId) {
        SecurityUtils.requireAdmin();
        jdbcTemplate.update("DELETE FROM users WHERE id = :id", new MapSqlParameterSource("id", userId));
        return Map.of("message", "User deleted successfully.");
    }

    private Map<String, Object> loadUser(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM users WHERE id = :id LIMIT 1", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found.");
        }
        return rows.getFirst();
    }

    private String required(Map<String, Object> body, String key) {
        String value = stringValue(body.get(key));
        if (!StringUtils.hasText(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " is required.");
        }
        return value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
