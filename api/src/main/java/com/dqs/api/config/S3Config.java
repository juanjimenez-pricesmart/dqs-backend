package com.dqs.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The S3 client, present only when uploads are switched on.
 *
 * Legacy's AWSService reads the same six AWS_* variables, and QuoteCenter reads
 * them from the same place — the credentials were already in the backend's
 * .env.local. What it does not copy is how legacy talks to S3: the SDK in dev,
 * but a shell-out to the `aws` CLI in staging and production
 * (AWSService::detectEnvironment). We use the SDK everywhere, which means the
 * credentials have to work from inside the application in those environments
 * too — the CLI detour rather suggests they may not, and that is a deployment
 * question this code cannot answer.
 *
 * The bean is conditional so a deployment with no credentials still starts.
 * QuotationDocumentService takes it as an Optional and refuses uploads rather
 * than failing at construction, exactly as legacy answers `aws_disabled`.
 */
@Slf4j
@Configuration
public class S3Config {

    @Bean
    @ConditionalOnProperty(name = "quotecenter.s3.enabled", havingValue = "true")
    public S3Client s3Client(
            @Value("${aws.access-key-id}") String accessKeyId,
            @Value("${aws.secret-access-key}") String secretAccessKey,
            @Value("${aws.region}") String region) {

        log.info("[S3Config] S3 uploads enabled, region={}", region);
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();
    }
}
