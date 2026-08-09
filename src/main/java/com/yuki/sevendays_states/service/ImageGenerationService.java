package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SurvivorKarenProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Generates one PNG with Nova Canvas and stores it in the configured S3 bucket. */
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

  private final BedrockRuntimeClient bedrockRuntimeClient;
  private final S3Client s3Client;
  private final SurvivorKarenProperties properties;
  private final ObjectMapper objectMapper;

  public String generateAndStore(String prompt, String negativePrompt, String objectKey, long seed) {
    if (!properties.imageConfigured()) {
      throw new ImageGenerationException("Karen image generation is not fully configured.");
    }
    try {
      byte[] requestBody = objectMapper.writeValueAsBytes(Map.of(
          "taskType", "TEXT_IMAGE",
          "textToImageParams", Map.of(
              "text", prompt,
              "negativeText", negativePrompt),
          "imageGenerationConfig", Map.of(
              "seed", Math.floorMod(seed, 858_993_460L),
              "quality", "standard",
              "height", 1024,
              "width", 1024,
              "numberOfImages", 1)));
      var response = bedrockRuntimeClient.invokeModel(InvokeModelRequest.builder()
          .modelId(properties.imageModelId())
          .contentType("application/json")
          .accept("application/json")
          .body(SdkBytes.fromByteArray(requestBody))
          .build());
      JsonNode responseJson = objectMapper.readTree(response.body().asByteArray());
      JsonNode images = responseJson.path("images");
      if (!images.isArray() || images.isEmpty() || images.get(0).asText().isBlank()) {
        throw new ImageGenerationException("Bedrock image response did not contain an image.");
      }
      byte[] imageBytes = Base64.getDecoder().decode(images.get(0).asText());
      s3Client.putObject(PutObjectRequest.builder()
              .bucket(properties.imageBucket())
              .key(objectKey)
              .contentType("image/png")
              .cacheControl("public, max-age=31536000, immutable")
              .build(),
          RequestBody.fromBytes(imageBytes));
      return publicUrl(objectKey);
    } catch (ImageGenerationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ImageGenerationException("Karen image generation or upload failed.", exception);
    } catch (Exception exception) {
      throw new ImageGenerationException("Karen image response could not be processed.", exception);
    }
  }

  private String publicUrl(String objectKey) {
    String baseUrl = properties.imagePublicBaseUrl().isBlank()
        ? "https://%s.s3.%s.amazonaws.com".formatted(
            properties.imageBucket(), properties.awsRegion())
        : properties.imagePublicBaseUrl().replaceAll("/+$", "");
    String encodedKey = Arrays.stream(objectKey.split("/"))
        .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
        .collect(Collectors.joining("/"));
    return baseUrl + "/" + encodedKey;
  }

  public static class ImageGenerationException extends RuntimeException {
    public ImageGenerationException(String message) { super(message); }
    public ImageGenerationException(String message, Throwable cause) { super(message, cause); }
  }
}
