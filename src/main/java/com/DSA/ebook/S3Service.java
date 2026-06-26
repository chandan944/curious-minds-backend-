package com.DSA.ebook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Service
public class S3Service {

    private final S3Client s3Client;
    private final com.DSA.common.OperationTracker operationTracker;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.cloudfront.url}")
    private String cloudFrontUrl;

    @Value("${aws.s3.cover.bucket}")
    private String coverBucketName;

    @Value("${aws.cloudfront.cover.url}")
    private String coverCloudFrontUrl;

    public S3Service(S3Client s3Client, com.DSA.common.OperationTracker operationTracker) {
        this.s3Client = s3Client;
        this.operationTracker = operationTracker;
    }

    public String uploadFile(MultipartFile file) throws IOException {
        return uploadFile(file, false);
    }

    public String uploadFile(MultipartFile file, boolean isCover) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        
        String folder = isCover ? "covers/" : "ebooks/";
        String key = folder + UUID.randomUUID().toString() + extension;

        String targetBucket = isCover ? coverBucketName : bucketName;
        String targetCloudFrontUrl = isCover ? coverCloudFrontUrl : cloudFrontUrl;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(targetBucket)
                .key(key)
                .contentType(file.getContentType())
                .build();

        // Track R2 Class A Operation (Upload)
        operationTracker.trackR2ClassA();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        // Return the CloudFront URL for the uploaded object
        return targetCloudFrontUrl.endsWith("/") ? targetCloudFrontUrl + key : targetCloudFrontUrl + "/" + key;
    }

    public void deleteFile(String fileUrl) {
        deleteFile(fileUrl, false);
    }

    public void deleteFile(String fileUrl, boolean isCover) {
        if (fileUrl == null || fileUrl.trim().isEmpty()) return;
        try {
            String folder = isCover ? "/covers/" : "/ebooks/";
            int index = fileUrl.indexOf(folder);
            if (index == -1) {
                System.err.println("❌ Failed to delete from S3: Unrecognized file URL format " + fileUrl);
                return;
            }
            String key = fileUrl.substring(index + 1);
            String targetBucket = isCover ? coverBucketName : bucketName;

            // Track R2 Class B Operation (Delete)
            operationTracker.trackR2ClassB();

            s3Client.deleteObject(builder -> builder.bucket(targetBucket).key(key).build());
        } catch (Exception e) {
            System.err.println("❌ Failed to delete from S3: " + e.getMessage());
        }
    }
}
