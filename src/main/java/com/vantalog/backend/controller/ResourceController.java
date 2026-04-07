package com.vantalog.backend.controller;

import com.vantalog.backend.security.AuthenticatedUser;
import com.vantalog.backend.service.FileStorageService;
import com.vantalog.backend.util.ApiException;
import com.vantalog.backend.util.ResponseMappers;
import com.vantalog.backend.util.SecurityUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final FileStorageService fileStorageService;

    public ResourceController(NamedParameterJdbcTemplate jdbcTemplate, FileStorageService fileStorageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public Map<String, Object> allResources(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "12") int size,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String subject,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false) String sort) {
        return findResources(page, size, category, subject, type, search, sort);
    }

    @GetMapping("/search")
    public Map<String, Object> searchResources(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "12") int size,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String subject,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String query,
                                               @RequestParam(required = false) String search) {
        return findResources(page, size, category, subject, type, StringUtils.hasText(query) ? query : search, null);
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return jdbcTemplate.queryForList("""
                SELECT category, COUNT(*) AS resourceCount
                FROM resources
                WHERE active = 1
                GROUP BY category
                ORDER BY category ASC
                """, new MapSqlParameterSource()).stream().map(row -> Map.<String, Object>of(
                "category", row.get("category"),
                "resourceCount", row.get("resourceCount")
        )).toList();
    }

    @GetMapping("/featured")
    public List<Map<String, Object>> featured(@RequestParam(defaultValue = "6") int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT *
                FROM resources
                WHERE active = 1
                ORDER BY is_featured DESC, average_rating DESC, created_at DESC
                LIMIT :limitValue
                """, new MapSqlParameterSource("limitValue", Math.max(1, Math.min(limit, 20))));
        return rows.stream().map(ResponseMappers::normalizeResource).toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        SecurityUtils.requireAdmin();
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS totalResources,
                       COALESCE(SUM(download_count), 0) AS totalDownloads,
                       COALESCE(SUM(view_count), 0) AS totalViews
                FROM resources
                WHERE active = 1
                """, new MapSqlParameterSource());
        List<Map<String, Object>> byCategory = jdbcTemplate.queryForList("""
                SELECT category, COUNT(*) AS total
                FROM resources
                WHERE active = 1
                GROUP BY category
                ORDER BY total DESC
                """, new MapSqlParameterSource());
        List<Map<String, Object>> recentUploads = jdbcTemplate.queryForList("""
                SELECT r.*, u.name AS uploaderName
                FROM resources r
                INNER JOIN users u ON u.id = r.uploaded_by
                WHERE r.active = 1
                ORDER BY r.created_at DESC
                LIMIT 5
                """, new MapSqlParameterSource()).stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>(ResponseMappers.normalizeResource(row));
            normalized.put("uploaderName", row.get("uploaderName"));
            return normalized;
        }).toList();
        return Map.of(
                "totalResources", counts.get("totalResources"),
                "totalDownloads", counts.get("totalDownloads"),
                "totalViews", counts.get("totalViews"),
                "byCategory", byCategory,
                "recentUploads", recentUploads
        );
    }

    @GetMapping("/category/{category}")
    public Map<String, Object> resourcesByCategory(@PathVariable String category,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "12") int size,
                                                   @RequestParam(required = false) String subject,
                                                   @RequestParam(required = false) String type) {
        return findResources(page, size, category, subject, type, null, null);
    }

    @GetMapping("/{resourceId}/ratings")
    public Map<String, Object> ratings(@PathVariable String resourceId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        int offset = Math.max(page - 1, 0) * Math.max(size, 1);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT rr.id, rr.rating, rr.comment, rr.created_at, u.name
                FROM resource_ratings rr
                INNER JOIN users u ON u.id = rr.user_id
                WHERE rr.resource_id = :resourceId
                ORDER BY rr.updated_at DESC
                LIMIT :size OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("resourceId", resourceId)
                .addValue("size", Math.max(size, 1))
                .addValue("offset", offset));
        Map<String, Object> summary = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS totalRatings, COALESCE(AVG(rating), 0) AS averageRating
                FROM resource_ratings WHERE resource_id = :resourceId
                """, new MapSqlParameterSource("resourceId", resourceId));
        return Map.of(
                "ratings", rows.stream().map(row -> Map.<String, Object>of(
                        "id", row.get("id"),
                        "rating", row.get("rating"),
                        "comment", row.get("comment") == null ? "" : row.get("comment"),
                        "userName", row.get("name"),
                        "createdAt", row.get("created_at")
                )).toList(),
                "totalRatings", summary.get("totalRatings"),
                "averageRating", ResponseMappers.decimalValue(summary.get("averageRating"))
        );
    }

    @PostMapping("/{resourceId}/rate")
    public Map<String, Object> rate(@PathVariable String resourceId, @RequestBody Map<String, Object> body) {
        AuthenticatedUser user = SecurityUtils.requireUser();
        int rating = Integer.parseInt(String.valueOf(body.get("rating")));
        if (rating < 1 || rating > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5.");
        }
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));

        Map<String, Object> resource = activeResourceOrThrow(resourceId);
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                SELECT id FROM resource_ratings WHERE user_id = :userId AND resource_id = :resourceId LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", user.id())
                .addValue("resourceId", resourceId));
        if (existing.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO resource_ratings (id, user_id, resource_id, rating, comment)
                    VALUES (:id, :userId, :resourceId, :rating, :comment)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString())
                    .addValue("userId", user.id())
                    .addValue("resourceId", resourceId)
                    .addValue("rating", rating)
                    .addValue("comment", comment));
        } else {
            jdbcTemplate.update("""
                    UPDATE resource_ratings SET rating = :rating, comment = :comment WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", existing.getFirst().get("id"))
                    .addValue("rating", rating)
                    .addValue("comment", comment));
        }

        jdbcTemplate.update("""
                INSERT INTO feedback (id, name, email, category, subject, message, rating, status)
                VALUES (:id, :name, :email, :category, :subject, :message, :rating, 'pending')
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString())
                .addValue("name", user.name())
                .addValue("email", user.email().toLowerCase())
                .addValue("category", resource.get("category"))
                .addValue("subject", resource.get("subject") + " - " + resource.get("title"))
                .addValue("message", StringUtils.hasText(comment) ? comment.trim() : "Rated \"" + resource.get("title") + "\" with " + rating + " stars.")
                .addValue("rating", rating));

        jdbcTemplate.update("""
                UPDATE resources
                SET average_rating = (SELECT COALESCE(AVG(rating), 0) FROM resource_ratings WHERE resource_id = :resourceId)
                WHERE id = :resourceId
                """, new MapSqlParameterSource("resourceId", resourceId));
        return Map.of("message", "Rating submitted successfully.");
    }

    @PostMapping("/{resourceId}/track")
    public Map<String, Object> track(@PathVariable String resourceId) {
        AuthenticatedUser user = SecurityUtils.requireUser();
        jdbcTemplate.update("""
                INSERT INTO resource_access_logs (id, user_id, resource_id, access_type)
                VALUES (:id, :userId, :resourceId, 'view')
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString())
                .addValue("userId", user.id())
                .addValue("resourceId", resourceId));
        jdbcTemplate.update("UPDATE resources SET view_count = view_count + 1 WHERE id = :resourceId",
                new MapSqlParameterSource("resourceId", resourceId));
        return Map.of("message", "Resource access tracked.");
    }

    @GetMapping("/{resourceId}/download")
    public ResponseEntity<Resource> download(@PathVariable String resourceId) {
        AuthenticatedUser user = SecurityUtils.requireUser();
        Map<String, Object> resource = activeResourceOrThrow(resourceId);
        String storedName = extractStoredFileName(ResponseMappers.stringValue(resource.get("file_path")));
        Path filePath = fileStorageService.resolveStoredPath(storedName);
        if (!Files.exists(filePath)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File not found on disk.");
        }
        jdbcTemplate.update("""
                INSERT INTO resource_access_logs (id, user_id, resource_id, access_type)
                VALUES (:id, :userId, :resourceId, 'download')
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString())
                .addValue("userId", user.id())
                .addValue("resourceId", resourceId));
        jdbcTemplate.update("UPDATE resources SET download_count = download_count + 1 WHERE id = :id",
                new MapSqlParameterSource("id", resourceId));

        FileSystemResource fileResource = new FileSystemResource(filePath);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ResponseMappers.stringValue(resource.get("mime_type")) == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : ResponseMappers.stringValue(resource.get("mime_type"))))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(ResponseMappers.stringValue(resource.get("file_name"))).build().toString())
                .body(fileResource);
    }

    @GetMapping("/{resourceId}")
    public Map<String, Object> one(@PathVariable String resourceId) {
        return ResponseMappers.normalizeResource(activeResourceOrThrow(resourceId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@RequestParam String title,
                                      @RequestParam String category,
                                      @RequestParam String subject,
                                      @RequestParam String resourceType,
                                      @RequestParam String description,
                                      @RequestParam MultipartFile file,
                                      @RequestParam(required = false) String isFeatured) throws IOException {
        AuthenticatedUser user = SecurityUtils.requireAdmin();
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Title, category, subject, resource type, description, and file are required.");
        }
        String storedName = fileStorageService.store(file);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO resources (
                    id, title, category, subject, resource_type, description,
                    file_name, file_path, mime_type, size_bytes, uploaded_by, is_featured
                ) VALUES (
                    :id, :title, :category, :subject, :resourceType, :description,
                    :fileName, :filePath, :mimeType, :sizeBytes, :uploadedBy, :isFeatured
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("title", title)
                .addValue("category", category)
                .addValue("subject", subject)
                .addValue("resourceType", resourceType)
                .addValue("description", description)
                .addValue("fileName", file.getOriginalFilename())
                .addValue("filePath", "uploads/" + storedName)
                .addValue("mimeType", file.getContentType())
                .addValue("sizeBytes", file.getSize())
                .addValue("uploadedBy", user.id())
                .addValue("isFeatured", "true".equalsIgnoreCase(isFeatured) ? 1 : 0));
        Map<String, Object> resource = jdbcTemplate.queryForList("SELECT * FROM resources WHERE id = :id LIMIT 1",
                new MapSqlParameterSource("id", id)).getFirst();
        return Map.of("message", "Resource uploaded successfully.", "resourceId", id, "resource", ResponseMappers.normalizeResource(resource));
    }

    @PutMapping(path = "/{resourceId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> update(@PathVariable String resourceId,
                                      @RequestParam(required = false) String title,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String subject,
                                      @RequestParam(required = false) String resourceType,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(required = false) String isFeatured,
                                      @RequestParam(required = false) MultipartFile file) throws IOException {
        SecurityUtils.requireAdmin();
        Map<String, Object> existing = resourceOrThrow(resourceId);

        String fileName = ResponseMappers.stringValue(existing.get("file_name"));
        String filePath = ResponseMappers.stringValue(existing.get("file_path"));
        String mimeType = ResponseMappers.stringValue(existing.get("mime_type"));
        Object sizeBytes = existing.get("size_bytes");

        if (file != null && !file.isEmpty()) {
            fileStorageService.deleteIfExists(extractStoredFileName(filePath));
            String storedName = fileStorageService.store(file);
            fileName = file.getOriginalFilename();
            filePath = "uploads/" + storedName;
            mimeType = file.getContentType();
            sizeBytes = file.getSize();
        }

        jdbcTemplate.update("""
                UPDATE resources
                SET title = :title,
                    category = :category,
                    subject = :subject,
                    resource_type = :resourceType,
                    description = :description,
                    file_name = :fileName,
                    file_path = :filePath,
                    mime_type = :mimeType,
                    size_bytes = :sizeBytes,
                    is_featured = :isFeatured
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", resourceId)
                .addValue("title", StringUtils.hasText(title) ? title : existing.get("title"))
                .addValue("category", StringUtils.hasText(category) ? category : existing.get("category"))
                .addValue("subject", StringUtils.hasText(subject) ? subject : existing.get("subject"))
                .addValue("resourceType", StringUtils.hasText(resourceType) ? resourceType : existing.get("resource_type"))
                .addValue("description", StringUtils.hasText(description) ? description : existing.get("description"))
                .addValue("fileName", fileName)
                .addValue("filePath", filePath)
                .addValue("mimeType", mimeType)
                .addValue("sizeBytes", sizeBytes)
                .addValue("isFeatured", isFeatured == null ? existing.get("is_featured") : ("true".equalsIgnoreCase(isFeatured) ? 1 : 0)));

        Map<String, Object> updated = resourceOrThrow(resourceId);
        return Map.of("message", "Resource updated successfully.", "resource", ResponseMappers.normalizeResource(updated));
    }

    @DeleteMapping("/{resourceId}")
    public Map<String, Object> delete(@PathVariable String resourceId) {
        SecurityUtils.requireAdmin();
        jdbcTemplate.update("UPDATE resources SET active = 0 WHERE id = :id", new MapSqlParameterSource("id", resourceId));
        return Map.of("message", "Resource deleted successfully.");
    }

    private Map<String, Object> findResources(int page, int size, String category, String subject, String type, String search, String sort) {
        int pageValue = Math.max(page, 1);
        int sizeValue = Math.min(Math.max(size, 1), 50);
        int offset = (pageValue - 1) * sizeValue;
        StringBuilder where = new StringBuilder("r.active = 1");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("size", sizeValue)
                .addValue("offset", offset);

        if (StringUtils.hasText(category)) {
            where.append(" AND LOWER(r.category) = :category");
            params.addValue("category", category.toLowerCase());
        }
        if (StringUtils.hasText(subject)) {
            where.append(" AND LOWER(r.subject) = :subject");
            params.addValue("subject", subject.toLowerCase());
        }
        if (StringUtils.hasText(type)) {
            where.append(" AND LOWER(r.resource_type) = :type");
            params.addValue("type", type.toLowerCase());
        }
        if (StringUtils.hasText(search)) {
            where.append(" AND (r.title LIKE :keyword OR r.description LIKE :keyword OR r.subject LIKE :keyword)");
            params.addValue("keyword", "%" + search + "%");
        }

        String order = "title".equalsIgnoreCase(sort) ? "r.title ASC" : "r.created_at DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.* FROM resources r
                WHERE %s
                ORDER BY %s
                LIMIT :size OFFSET :offset
                """.formatted(where, order), params);
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM resources r WHERE " + where, params, Integer.class);
        return Map.of(
                "resources", rows.stream().map(ResponseMappers::normalizeResource).toList(),
                "totalElements", total == null ? 0 : total,
                "totalPages", total == null ? 0 : (int) Math.ceil((double) total / sizeValue),
                "currentPage", pageValue
        );
    }

    private Map<String, Object> resourceOrThrow(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM resources WHERE id = :id LIMIT 1", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Resource not found.");
        }
        return rows.getFirst();
    }

    private Map<String, Object> activeResourceOrThrow(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM resources WHERE id = :id AND active = 1 LIMIT 1", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Resource not found.");
        }
        return rows.getFirst();
    }

    private String extractStoredFileName(String filePath) {
        String normalized = filePath == null ? "" : filePath.replace("\\", "/");
        return normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
    }
}
