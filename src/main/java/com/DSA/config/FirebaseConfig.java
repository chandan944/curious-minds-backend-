package com.DSA.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

@Configuration
public class FirebaseConfig {

    @Value("${firebase.config.path:}")
    private String firebaseConfigPath;

    @Bean
    public Firestore firestore() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            GoogleCredentials credentials;
            
            if (firebaseConfigPath != null && !firebaseConfigPath.trim().isEmpty()) {
                System.out.println("🔥 Initializing Firebase from configured path: " + firebaseConfigPath);
                try (InputStream is = new FileInputStream(firebaseConfigPath)) {
                    credentials = GoogleCredentials.fromStream(is);
                }
            } else {
                System.out.println("🔥 Initializing Firebase from classpath or application default credentials");
                // Try loading from classpath as fallback
                try (InputStream serviceAccount = getClass().getClassLoader().getResourceAsStream("firebase-sa.json")) {
                    if (serviceAccount != null) {
                        System.out.println("🔥 Found firebase-sa.json in classpath");
                        credentials = GoogleCredentials.fromStream(serviceAccount);
                    } else {
                        System.out.println("🔥 Falling back to Google Application Default Credentials (ADC)");
                        credentials = GoogleCredentials.getApplicationDefault();
                    }
                } catch (Exception e) {
                    System.out.println("⚠️ Classpath lookup failed: " + e.getMessage() + ". Falling back to ADC.");
                    credentials = GoogleCredentials.getApplicationDefault();
                }
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
            System.out.println("✅ Firebase Application initialized successfully.");
        }

        return FirestoreClient.getFirestore();
    }
}
