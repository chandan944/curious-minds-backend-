package com.DSA.controller;

import com.DSA.auth.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

/**
 * Proxies PDF file requests from the in-app viewer.
 * This avoids CORS issues when PDF.js (running in WebView) tries to 
 * fetch a PDF directly from CloudFront (which has no CORS headers).
 * 
 * Auth is done via query parameter `token` since PDF.js in WebView
 * cannot set Authorization headers on its fetch requests.
 */
@RestController
@RequestMapping("/api/ebooks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EbookProxyController {

    private final JwtService jwtService;
    private final RestTemplate pdfProxyRestTemplate;

    @GetMapping("/proxy")
    public ResponseEntity<byte[]> proxyPdf(
            @RequestParam String url,
            @RequestParam(required = false) String token) {

        // Auth via query parameter (PDF.js can't set headers)
        if (token == null || token.isEmpty()) {
            return ResponseEntity.status(401).body("Unauthorized: token required".getBytes());
        }
        try {
            Claims claims = jwtService.extractClaims(token);
            if (claims.get("userId", Long.class) == null) {
                return ResponseEntity.status(401).body("Unauthorized: invalid token".getBytes());
            }
        } catch (Exception e) {
            return ResponseEntity.status(401).body("Unauthorized: token expired or invalid".getBytes());
        }

        // Only allow proxying from our own CloudFront domains
        if (!url.contains("cloudfront.net") && !url.contains("curiousminds")) {
            return ResponseEntity.status(403).body("Forbidden: Only internal PDF URLs are allowed".getBytes());
        }

        try {
            // Fetch the PDF from CloudFront (server-to-server, no CORS restriction)
            ResponseEntity<byte[]> response = pdfProxyRestTemplate.exchange(
                    url, HttpMethod.GET, null, byte[].class);

            if (response.getBody() == null) {
                return ResponseEntity.notFound().build();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentLength(response.getBody().length);
            // Prevent browser from offering download dialog
            headers.setContentDisposition(ContentDisposition.inline().build());
            // Cache for 1 hour on the client (subsequent opens are instant)
            headers.setCacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)));

            return new ResponseEntity<>(response.getBody(), headers, HttpStatus.OK);

        } catch (Exception e) {
            System.err.println("❌ PDF proxy failed for URL: " + url + " — " + e.getMessage());
            return ResponseEntity.status(502).body(("Proxy error: " + e.getMessage()).getBytes());
        }
    }
}
