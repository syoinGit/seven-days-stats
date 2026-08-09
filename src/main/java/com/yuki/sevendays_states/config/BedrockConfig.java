package com.yuki.sevendays_states.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class BedrockConfig {

  @Bean(destroyMethod = "close")
  BedrockRuntimeClient bedrockRuntimeClient(AiAnalysisProperties properties) {
    return BedrockRuntimeClient.builder()
        .region(Region.of(properties.awsRegion()))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }

  @Bean(destroyMethod = "close")
  S3Client s3Client(SurvivorKarenProperties properties) {
    return S3Client.builder()
        .region(Region.of(properties.awsRegion()))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }
}
