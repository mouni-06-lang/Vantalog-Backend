package com.vantalog.backend.controller;

import com.vantalog.backend.security.AuthenticatedUser;
import com.vantalog.backend.util.ApiException;
import com.vantalog.backend.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public FeedbackController(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        String name = required(body, "name");
        String email = required(body, "email").toLowerCase();
        String category = StringUtils.hasText(stringValue(body.get("category"))) ? stringValue(body.get("category")) : "general";
        String message = required(body, "message");
        Integer rating = body.get("rating") == null ? null : Integer.parseInt(String.valueOf(body.get("rating")));
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO feedback (id, name, email, category, message, rating, status)
                VALUES (:id, :name, :email, :category, :message, :rating, 'pending')
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("email", email)
                .addValue("category", category)
                .addValue("message", message)
                .addValue("rating", rating));
        return Map.of("message", "Feedback submitted successfully.", "feedbackId", id);
    }

    @PostMapping("/contact")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> contact(@RequestBody Map<String, Object> body) {
        String name = required(body, "name");
        String email = required(body, "email").toLowerCase();
        String subject = required(body, "subject");
        String message = required(body, "message");
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO feedback (id, name, email, category, subject, message, status)
                VALUES (:id, :name, :email, 'contact', :subject, :message, 'pending')
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("email", email)
                .addValue("subject", subject)
                .addValue("message", message));
        return Map.of("message", "Message sent successfully.", "feedbackId", id);
    }

    @GetMapping
    public Map<String, Object> all(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int size,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String category) {
        SecurityUtils.requireAdmin();
        int pageValue = Math.max(page, 1);
        int sizeValue = Math.min(Math.max(size, 1), 50);
        int offset = (pageValue - 1) * sizeValue;
        StringBuilder where = new StringBuilder("1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("size", sizeValue).addValue("offset", offset);
        if (StringUtils.hasText(status)) {
            where.append(" AND status = :status");
            params.addValue("status", status);
        }
        if (StringUtils.hasText(category)) {
            where.append(" AND category = :category");
            params.addValue("category", category);
        }
        List<Map<String, Object>> feedback = jdbcTemplate.queryForList("""
                SELECT * FROM feedback WHERE %s ORDER BY created_at DESC LIMIT :size OFFSET :offset
                """.formatted(where), params);
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feedback WHERE " + where, params, Integer.class);
        return Map.of(
                "feedback", feedback.stream().map(this::feedbackMap).toList(),
                "totalElements", total == null ? 0 : total,
                "totalPages", total == null ? 0 : (int) Math.ceil((double) total / sizeValue),
                "currentPage", pageValue
        );
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        SecurityUtils.requireAdmin();
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total, COALESCE(AVG(rating), 0) AS averageRating
                FROM feedback
                WHERE category <> 'contact'
                """, new MapSqlParameterSource());
        List<Map<String, Object>> byCategory = jdbcTemplate.queryForList("""
                SELECT category, COUNT(*) AS total FROM feedback GROUP BY category ORDER BY total DESC
                """, new MapSqlParameterSource());
        List<Map<String, Object>> byStatus = jdbcTemplate.queryForList("""
                SELECT status, COUNT(*) AS total FROM feedback GROUP BY status ORDER BY total DESC
                """, new MapSqlParameterSource());
        return Map.of(
                "total", totals.get("total"),
                "averageRating", totals.get("averageRating"),
                "byCategory", byCategory,
                "byStatus", byStatus
        );
    }

    @GetMapping("/{feedbackId}")
    public Map<String, Object> one(@PathVariable String feedbackId) {
        SecurityUtils.requireAdmin();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM feedback WHERE id = :id LIMIT 1",
                new MapSqlParameterSource("id", feedbackId));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Feedback not found.");
        }
        return feedbackMap(rows.getFirst());
    }

    @PutMapping("/{feedbackId}/status")
    public Map<String, Object> updateStatus(@PathVariable String feedbackId, @RequestBody Map<String, Object> body) {
        SecurityUtils.requireAdmin();
        jdbcTemplate.update("UPDATE feedback SET status = :status WHERE id = :id",
                new MapSqlParameterSource().addValue("status", body.get("status")).addValue("id", feedbackId));
        return Map.of("message", "Feedback status updated successfully.");
    }

    @PostMapping("/{feedbackId}/respond")
    public Map<String, Object> respond(@PathVariable String feedbackId, @RequestBody Map<String, Object> body) {
        AuthenticatedUser admin = SecurityUtils.requireAdmin();
        String response = required(body, "response");
        jdbcTemplate.update("""
                UPDATE feedback
                SET admin_response = :response, status = 'resolved', responded_by = :userId, responded_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("response", response)
                .addValue("userId", admin.id())
                .addValue("id", feedbackId));
        return Map.of("message", "Response saved successfully.");
    }

    @DeleteMapping("/{feedbackId}")
    public Map<String, Object> delete(@PathVariable String feedbackId) {
        SecurityUtils.requireAdmin();
        jdbcTemplate.update("DELETE FROM feedback WHERE id = :id", new MapSqlParameterSource("id", feedbackId));
        return Map.of("message", "Feedback deleted successfully.");
    }

    private Map<String, Object> feedbackMap(Map<String, Object> item) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", item.get("id"));
        response.put("name", item.get("name"));
        response.put("email", item.get("email"));
        response.put("category", item.get("category"));
        response.put("subject", item.get("subject") == null ? "" : item.get("subject"));
        response.put("message", item.get("message"));
        response.put("rating", item.get("rating"));
        response.put("status", item.get("status"));
        response.put("adminResponse", item.get("admin_response") == null ? "" : item.get("admin_response"));
        response.put("createdAt", item.get("created_at"));
        response.put("updatedAt", item.get("updated_at"));
        return response;
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
