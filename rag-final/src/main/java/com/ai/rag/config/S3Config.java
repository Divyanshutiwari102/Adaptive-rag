package com.ai.rag.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3Config — UPDATED.
 *
 * Changes:
 *   1. Added S3Presigner bean (for BONUS presigned upload URL feature).
 *   2. Credentials resolution order uses DefaultCredentialsProvider, which
 *      automatically tries (in order):
 *        a) Environment variables: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
 *        b) Java system properties: aws.accessKeyId, aws.secretAccessKey
 *        c) AWS credentials file: ~/.aws/credentials
 *        d) IAM role (EC2/ECS instance profile) — RECOMMENDED for production
 *        e) EKS Pod Identity / IRSA
 *
 *   For local development, set environment variables in your .env or IDE run config.
 *   In production (EC2/ECS/EKS), attach an IAM role — never put credentials in code.
 *
 * LOCAL DEVELOPMENT with LocalStack:
 *   Set storage.s3.endpoint-override=http://localhost:4566 in application.yml.
 *   S3Client will use path-style access (required for LocalStack).
 *   LocalStack doesn't validate credentials, so any non-empty string works.
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final StorageProperties storageProperties;

    /**
     * S3Client bean — shared, thread-safe singleton.
     *
     * DefaultCredentialsProvider is used (resolves credentials from env vars,
     * system properties, ~/.aws/credentials, or IAM role — in that order).
     * This is the AWS-recommended approach; credentials are never hardcoded.
     *
     * endpointOverride is only set for local development (LocalStack).
     * In production, leave storage.s3.endpoint-override blank.
     */
    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(storageProperties.getS3().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());

        String override = storageProperties.getS3().getEndpointOverride();
        if (override != null && !override.isBlank()) {
            builder.endpointOverride(URI.create(override))
                    .forcePathStyle(true);  // required for LocalStack
        }
        return builder.build();
    }

    /**
     * S3Presigner bean — used for generating presigned upload URLs.
     *
     * Presigned URLs allow the browser/client to upload files DIRECTLY to S3
     * without routing through your application server. This eliminates the
     * file bytes from your app's memory and bandwidth entirely.
     *
     * Used by the BONUS presigned-URL feature in S3FileStorageService.
     * Remove this bean if you don't need presigned URLs.
     */
    @Bean
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(storageProperties.getS3().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());

        String override = storageProperties.getS3().getEndpointOverride();
        if (override != null && !override.isBlank()) {
            builder.endpointOverride(URI.create(override));
        }
        return builder.build();
    }
}