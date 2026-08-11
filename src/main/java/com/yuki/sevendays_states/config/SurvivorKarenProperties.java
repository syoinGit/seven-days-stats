package com.yuki.sevendays_states.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.survivor-karen")
public record SurvivorKarenProperties(
    boolean imageEnabled,
    int imageIntervalDays,
    int postHour,
    String awsRegion,
    String imageModelId,
    String imageBucket,
    String imagePrefix,
    String imagePublicBaseUrl
) {

  public SurvivorKarenProperties {
    imageIntervalDays = imageIntervalDays <= 0 ? 3 : Math.min(imageIntervalDays, 30);
    postHour = Math.max(0, Math.min(postHour, 23));
    awsRegion = blankOrDefault(awsRegion, "us-east-1");
    imageModelId = blankOrDefault(imageModelId, "amazon.nova-canvas-v1:0");
    imageBucket = imageBucket == null ? "" : imageBucket.strip();
    imagePrefix = trimSlashes(blankOrDefault(imagePrefix, "watchpoint/posts/survivor-karen"));
    imagePublicBaseUrl = imagePublicBaseUrl == null ? "" : imagePublicBaseUrl.strip();
  }

  public boolean imageConfigured() {
    return imageEnabled && !imageBucket.isBlank() && !imageModelId.isBlank();
  }

  private static String blankOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.strip();
  }

  private static String trimSlashes(String value) {
    return value.replaceAll("^/+|/+$", "");
  }
}
