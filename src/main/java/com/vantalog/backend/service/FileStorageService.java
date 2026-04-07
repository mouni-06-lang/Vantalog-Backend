package com.vantalog.backend.service;

import com.vantalog.backend.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class FileStorageService {
    private final Path uploadPath;

    public FileStorageService(AppProperties appProperties) throws IOException {
        this.uploadPath = Path.of(appProperties.uploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadPath);
    }

    public String store(MultipartFile file) throws IOException {
        String safeName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedName = System.currentTimeMillis() + "-" + safeName;
        Files.copy(file.getInputStream(), uploadPath.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
        return storedName;
    }

    public Path resolveStoredPath(String storedName) {
        return uploadPath.resolve(storedName).normalize();
    }

    public void deleteIfExists(String storedName) throws IOException {
        if (storedName == null || storedName.isBlank()) {
            return;
        }
        Files.deleteIfExists(resolveStoredPath(storedName));
    }
}
