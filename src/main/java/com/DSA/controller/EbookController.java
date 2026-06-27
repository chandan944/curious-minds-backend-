package com.DSA.controller;

import com.DSA.ebook.Ebook;
import com.DSA.ebook.EbookRepository;
import com.DSA.ebook.S3Service;
import com.DSA.user.UserRepository;
import com.DSA.common.ExpoNotificationService;
import com.DSA.social.Notification;
import com.DSA.social.NotificationRepository;
import com.DSA.social.NotificationType;
import com.DSA.config.ChatWebSocketHandler;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/ebooks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EbookController {

    private final S3Service s3Service;
    private final EbookRepository ebookRepository;
    private final UserRepository userRepository;
    private final ExpoNotificationService expoNotificationService;
    private final NotificationRepository notificationRepository;
    private final ChatWebSocketHandler chatWebSocketHandler;

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
            @RequestParam(value = "englishPdf", required = false) MultipartFile englishPdf,
            @RequestParam(value = "hindiPdf", required = false) MultipartFile hindiPdf,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "coverImage", required = false) MultipartFile coverImage) {

        try {
            if (isNotAdmin(authentication)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden: Only designated admin can upload eBooks"));
            }

            if ((englishPdf == null || englishPdf.isEmpty()) && (hindiPdf == null || hindiPdf.isEmpty())) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Please provide at least one PDF (English or Hindi)"));
            }

            String englishFileUrl = null;
            if (englishPdf != null && !englishPdf.isEmpty()) {
                if (!"application/pdf".equalsIgnoreCase(englishPdf.getContentType()) && 
                    (englishPdf.getOriginalFilename() == null || !englishPdf.getOriginalFilename().toLowerCase().endsWith(".pdf"))) {
                    return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Only PDF files are allowed for English version"));
                }
                englishFileUrl = s3Service.uploadFile(englishPdf);
            }

            String hindiFileUrl = null;
            if (hindiPdf != null && !hindiPdf.isEmpty()) {
                if (!"application/pdf".equalsIgnoreCase(hindiPdf.getContentType()) && 
                    (hindiPdf.getOriginalFilename() == null || !hindiPdf.getOriginalFilename().toLowerCase().endsWith(".pdf"))) {
                    return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Only PDF files are allowed for Hindi version"));
                }
                hindiFileUrl = s3Service.uploadFile(hindiPdf);
            }

            String coverImageUrl = null;
            if (coverImage != null && !coverImage.isEmpty()) {
                String contentType = coverImage.getContentType();
                String filename = coverImage.getOriginalFilename();
                boolean isImage = (contentType != null && contentType.startsWith("image/")) ||
                                  (filename != null && (filename.toLowerCase().endsWith(".jpg") || 
                                                        filename.toLowerCase().endsWith(".jpeg") || 
                                                        filename.toLowerCase().endsWith(".png") || 
                                                        filename.toLowerCase().endsWith(".webp")));
                if (isImage) {
                    coverImageUrl = s3Service.uploadFile(coverImage, true);
                } else {
                    System.err.println("⚠️ Rejected cover image upload: content type is " + contentType + ", filename is " + filename);
                }
            }

            String finalTitle = title;
            if (finalTitle == null || finalTitle.trim().isEmpty()) {
                if (englishPdf != null && !englishPdf.isEmpty()) {
                    finalTitle = englishPdf.getOriginalFilename();
                } else if (hindiPdf != null && !hindiPdf.isEmpty()) {
                    finalTitle = hindiPdf.getOriginalFilename();
                }
                
                if (finalTitle != null && finalTitle.toLowerCase().endsWith(".pdf")) {
                    finalTitle = finalTitle.substring(0, finalTitle.length() - 4);
                } else if (finalTitle == null) {
                    finalTitle = "Untitled Book";
                }
            }
            
            if (finalTitle != null) {
                try {
                    // Fix strange characters like %20 in filenames by URL decoding them
                    finalTitle = URLDecoder.decode(finalTitle, StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    // Ignore decoding errors
                }
            }

            Ebook ebook = Ebook.builder()
                    .title(finalTitle)
                    .description(description)
                    .englishFileUrl(englishFileUrl)
                    .hindiFileUrl(hindiFileUrl)
                    .coverImageUrl(coverImageUrl)
                    .uploaderEmail(authentication.getName())
                    .uploadedAt(Instant.now())
                    .build();

            ebookRepository.save(ebook);

            // Send notification to all users who have registered a push token
            try {
                List<com.DSA.user.User> allUsers = userRepository.findAll();
                List<String> tokens = new java.util.ArrayList<>();
                String uploaderEmail = authentication != null ? authentication.getName() : "";
                
                for (com.DSA.user.User u : allUsers) {
                    if (u == null) continue;
                    
                    // Exclude the uploader themselves from notifications
                    if (uploaderEmail != null && uploaderEmail.equalsIgnoreCase(u.getEmail())) {
                        continue;
                    }
                    
                    // 1. Save Notification record in Firestore
                    Notification notif = Notification.builder()
                            .recipient(u)
                            .type(NotificationType.NEW_EBOOK)
                            .senderId(0L) // System/Admin ID
                            .senderName("Library Admin")
                            .message("📚 " + ebook.getTitle() + " has just been added to the library.")
                            .isRead(false)
                            .createdAt(Instant.now())
                            .build();
                    notificationRepository.save(notif);
                    
                    // Collect push token if registered
                    if (u.getExpoPushToken() != null && !u.getExpoPushToken().trim().isEmpty()) {
                        tokens.add(u.getExpoPushToken());
                    }
                    
                    // 2. If user is online, push via WebSocket
                    if (chatWebSocketHandler.isUserOnline(u.getId())) {
                        JsonObject payload = new JsonObject();
                        payload.addProperty("type", "NOTIFICATION");
                        payload.addProperty("id", notif.getId());
                        payload.addProperty("notifType", NotificationType.NEW_EBOOK.name());
                        payload.addProperty("senderId", 0L);
                        payload.addProperty("senderName", "Library Admin");
                        payload.addProperty("senderImage", "");
                        payload.addProperty("message", notif.getMessage());
                        payload.addProperty("isRead", false);
                        payload.addProperty("createdAt", notif.getCreatedAt().toString());
                        
                        chatWebSocketHandler.sendToUser(u.getId(), payload);
                    }
                }
                        
                // 3. Send Expo Batch Push Notification
                if (!tokens.isEmpty()) {
                    Map<String, Object> data = new java.util.HashMap<>();
                    data.put("type", "NOTIFICATION");
                    data.put("notifType", NotificationType.NEW_EBOOK.name());
                    data.put("ebookId", ebook.getId());
                    
                    expoNotificationService.sendBatchPushNotifications(
                            tokens,
                            "New eBook Available!",
                            "📚 " + ebook.getTitle() + " has just been added to the library.",
                            data
                    );
                }
            } catch (Exception e) {
                System.err.println("⚠️ Failed to send ebook notifications: " + e.getMessage());
                e.printStackTrace();
            }

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
    public ResponseEntity<?> getAllEbooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        System.out.println("📚 [Backend] GET /api/ebooks called. Page: " + page + ", Size: " + size);
        org.springframework.data.domain.Page<Ebook> result = ebookRepository.findAllByOrderByUploadedAtDesc(
                org.springframework.data.domain.PageRequest.of(page, size));
        
        List<Ebook> ebooks = result.getContent();
        if (!ebooks.isEmpty()) {
            System.out.println("   [Backend] Returning " + ebooks.size() + " ebooks. First ebook details:");
            Ebook first = ebooks.get(0);
            System.out.println("             - Title: " + first.getTitle());
            System.out.println("             - ID Value: " + first.getId());
            System.out.println("             - ID Class: " + (first.getId() != null ? first.getId().getClass().getName() : "null"));
        } else {
            System.out.println("   [Backend] No ebooks found in database.");
        }
        
        return ResponseEntity.ok(Map.of(
                "content", result.getContent(),
                "totalPages", result.getTotalPages(),
                "totalElements", result.getTotalElements(),
                "currentPage", page
        ));
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
        System.out.println("🗑️ [Backend] DELETE request received for Ebook ID: " + id);
        try {
            if (isNotAdmin(authentication)) {
                System.out.println("❌ [Backend] Delete forbidden for user: " + authentication.getName());
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden"));
            }

            System.out.println("🔍 [Backend] Querying database for Ebook ID: " + id);
            Ebook ebook = ebookRepository.findById(id).orElse(null);
            if (ebook == null) {
                System.out.println("❌ [Backend] Ebook NOT found in database for ID: " + id);
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Ebook not found in database for ID: " + id));
            }

            System.out.println("✅ [Backend] Ebook found: '" + ebook.getTitle() + "'. Starting deletion...");

            // 1. Delete from S3
            if (ebook.getEnglishFileUrl() != null) {
                System.out.println("   Deleting English PDF: " + ebook.getEnglishFileUrl());
                s3Service.deleteFile(ebook.getEnglishFileUrl());
            }
            if (ebook.getHindiFileUrl() != null) {
                System.out.println("   Deleting Hindi PDF: " + ebook.getHindiFileUrl());
                s3Service.deleteFile(ebook.getHindiFileUrl());
            }
            if (ebook.getCoverImageUrl() != null) {
                System.out.println("   Deleting Cover Image: " + ebook.getCoverImageUrl());
                s3Service.deleteFile(ebook.getCoverImageUrl(), true);
            }

            // 2. Delete from DB
            System.out.println("   Deleting Ebook document from database...");
            ebookRepository.delete(ebook);

            System.out.println("🎉 [Backend] Ebook successfully deleted.");
            return ResponseEntity.ok(Map.of("success", true, "message", "Ebook deleted"));
        } catch (Exception e) {
            System.err.println("❌ [Backend] Exception during delete: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Delete failed: " + e.getMessage()));
        }
    }
}
