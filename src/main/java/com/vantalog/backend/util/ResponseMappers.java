package com.vantalog.backend.util;

import com.vantalog.backend.security.AuthenticatedUser;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ResponseMappers {
    private ResponseMappers() {
    }

    public static AuthenticatedUser publicUser(Map<String, Object> row) {
        return new AuthenticatedUser(
                String.valueOf(row.get("id")),
                stringValue(row.get("name")),
                stringValue(row.get("email")),
                stringValue(row.get("role")),
                stringValue(row.get("bio")),
                stringValue(row.get("avatar_url")),
                boolValue(row.get("active")),
                dateTime(row.get("created_at"))
        );
    }

    public static Map<String, Object> publicUserMap(Map<String, Object> row) {
        AuthenticatedUser user = publicUser(row);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.id());
        response.put("name", user.name());
        response.put("email", user.email());
        response.put("role", user.role());
        response.put("bio", user.bio() == null ? "" : user.bio());
        response.put("avatarUrl", user.avatarUrl() == null ? "" : user.avatarUrl());
        response.put("active", user.active());
        response.put("createdAt", user.createdAt());
        return response;
    }

    public static Map<String, Object> normalizeResource(Map<String, Object> row) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", stringValue(row.get("id")));
        response.put("title", stringValue(row.get("title")));
        response.put("category", stringValue(row.get("category")));
        response.put("subject", stringValue(row.get("subject")));
        response.put("type", stringValue(row.get("resource_type")));
        response.put("description", stringValue(row.get("description")));
        response.put("fileName", stringValue(row.get("file_name")));
        response.put("mimeType", stringValue(row.get("mime_type")));
        response.put("sizeBytes", row.get("size_bytes"));
        String filePath = stringValue(row.get("file_path"));
        String fileName = filePath == null ? "" : filePath.replace("\\", "/");
        if (fileName.contains("/")) {
            fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        }
        response.put("fileUrl", "/uploads/" + fileName);
        response.put("uploadedBy", stringValue(row.get("uploaded_by")));
        response.put("isFeatured", boolValue(row.get("is_featured")));
        response.put("viewCount", intValue(row.get("view_count")));
        response.put("downloadCount", intValue(row.get("download_count")));
        response.put("averageRating", decimalValue(row.get("average_rating")));
        response.put("createdAt", dateTime(row.get("created_at")));
        response.put("updatedAt", dateTime(row.get("updated_at")));
        return response;
    }

    public static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && !"0".equals(String.valueOf(value));
    }

    public static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static double decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    public static LocalDateTime dateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return null;
    }
}
