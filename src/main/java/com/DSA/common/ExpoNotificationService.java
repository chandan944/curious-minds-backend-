package com.DSA.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExpoNotificationService {

    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";
    private final RestTemplate restTemplate;

    @Value("${expo.access.token:}")
    private String expoAccessToken;

    public ExpoNotificationService() {
        this.restTemplate = new RestTemplate();
    }

    public void sendPushNotification(String to, String title, String body, Map<String, Object> data) {
        if (to == null || to.trim().isEmpty()) {
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Add Expo authentication header only if a valid custom token is provided
            if (expoAccessToken != null && !expoAccessToken.isEmpty() && !expoAccessToken.equalsIgnoreCase("YOUR_EXPO_ACCESS_TOKEN_HERE")) {
                headers.set("Authorization", "Bearer " + expoAccessToken);
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("to", to);
            payload.put("sound", "default");
            payload.put("title", title);
            payload.put("body", body);
            if (data != null) {
                payload.put("data", data);
            }

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(EXPO_PUSH_URL, request, String.class);

            System.out.println("Push notification sent to " + to + ": " + response.getStatusCode());
        } catch (Exception e) {
            System.err.println("Failed to send push notification to " + to + ": " + e.getMessage());
        }
    }

    public void sendBatchPushNotifications(List<String> toList, String title, String body, Map<String, Object> data) {
        if (toList == null || toList.isEmpty()) {
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Add Expo authentication header only if a valid custom token is provided
            if (expoAccessToken != null && !expoAccessToken.isEmpty() && !expoAccessToken.equalsIgnoreCase("YOUR_EXPO_ACCESS_TOKEN_HERE")) {
                headers.set("Authorization", "Bearer " + expoAccessToken);
            }

            List<Map<String, Object>> payloads = new ArrayList<>();
            for (String to : toList) {
                if (to != null && !to.trim().isEmpty()) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("to", to);
                    payload.put("sound", "default");
                    payload.put("title", title);
                    payload.put("body", body);
                    if (data != null) {
                        payload.put("data", data);
                    }
                    payloads.add(payload);
                }
            }

            if (payloads.isEmpty())
                return;

            // Chunk payloads into batches of 100 as per Expo API limitations
            int batchSize = 100;
            for (int i = 0; i < payloads.size(); i += batchSize) {
                List<Map<String, Object>> chunk = payloads.subList(i, Math.min(i + batchSize, payloads.size()));
                HttpEntity<List<Map<String, Object>>> request = new HttpEntity<>(chunk, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(EXPO_PUSH_URL, request, String.class);
                System.out.println(
                        "Batch push notification chunk sent to " + chunk.size() + " devices: " + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println("Failed to send batch push notifications: " + e.getMessage());
        }
    }
}
