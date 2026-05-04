package com.DSA.controller;

import com.DSA.ebook.Ebook;
import com.DSA.ebook.EbookRepository;
import com.DSA.ebook.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/ebooks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EbookController {

    private final S3Service s3Service;
    private final EbookRepository ebookRepository;

    // Admin who is allowed to perform restricted actions
    private static final String ALLOWED_ADMIN_EMAIL = "chandanprajapati6307@gmail.com";

    private boolean isNotAdmin(Authentication auth) {
        if (auth == null) return true;
        
        boolean hasAdminRole = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                
        String email = auth.getName(); // We set principal as email in JwtAuthenticationFilter
        return !hasAdminRole && !ALLOWED_ADMIN_EMAIL.equalsIgnoreCase(email);
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadEbook(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "coverImage", required = false) MultipartFile coverImage) {

        try {
            if (isNotAdmin(authentication)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden: Only designated admin can upload eBooks"));
            }

            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "File is empty"));
            }

            if (!"application/pdf".equalsIgnoreCase(file.getContentType()) && 
                (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase().endsWith(".pdf"))) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Only PDF files are allowed"));
            }

            String fileUrl = s3Service.uploadFile(file);

            String coverImageUrl = null;
            if (coverImage != null && !coverImage.isEmpty()) {
                if (coverImage.getContentType() != null && coverImage.getContentType().startsWith("image/")) {
                    coverImageUrl = s3Service.uploadFile(coverImage, true);
                }
            }

            Ebook ebook = Ebook.builder()
                    .title(title)
                    .description(description)
                    .fileUrl(fileUrl)
                    .coverImageUrl(coverImageUrl)
                    .uploaderEmail(authentication.getName())
                    .uploadedAt(LocalDateTime.now())
                    .build();

            ebookRepository.save(ebook);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Ebook uploaded successfully",
                    "ebook", ebook
            ));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Failed to upload: " + e.getMessage()));
        }
    }
    
    @GetMapping
    public ResponseEntity<?> getAllEbooks() {
        return ResponseEntity.ok(ebookRepository.findAllByOrderByUploadedAtDesc());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateEbook(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        try {
            if (isNotAdmin(authentication)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden"));
            }

            Ebook ebook = ebookRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Ebook not found"));

            if (body.containsKey("title")) ebook.setTitle(body.get("title"));
            if (body.containsKey("description")) ebook.setDescription(body.get("description"));

            ebookRepository.save(ebook);
            return ResponseEntity.ok(Map.of("success", true, "message", "Ebook updated", "ebook", ebook));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEbook(
            Authentication authentication,
            @PathVariable Long id) {
        try {
            if (isNotAdmin(authentication)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden"));
            }

            Ebook ebook = ebookRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Ebook not found"));

            // 1. Delete from S3
            s3Service.deleteFile(ebook.getFileUrl());
            if (ebook.getCoverImageUrl() != null) {
                s3Service.deleteFile(ebook.getCoverImageUrl(), true);
            }

            // 2. Delete from DB
            ebookRepository.delete(ebook);

            return ResponseEntity.ok(Map.of("success", true, "message", "Ebook deleted"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
