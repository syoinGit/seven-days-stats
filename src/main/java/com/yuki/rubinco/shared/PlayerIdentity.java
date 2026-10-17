package com.yuki.rubinco.shared;

import java.util.ArrayList;
import java.util.List;

/** Builds stable player keys across EOS, Steam, and platform-specific log formats. */
public final class PlayerIdentity {

  private PlayerIdentity() {
  }

  public static String canonicalPlayerKey(
      String platform,
      String userId,
      String nativePlatform,
      String nativeUserId) {
    return firstNonBlank(eosKey(platform, userId), eosKey(nativePlatform, nativeUserId),
        steamKey(platform, userId), steamKey(nativePlatform, nativeUserId),
        platformKey(platform, userId));
  }

  public static List<String> candidatePlayerKeys(
      String platform,
      String userId,
      String nativePlatform,
      String nativeUserId) {
    List<String> keys = new ArrayList<>();
    addIfPresent(keys, eosKey(platform, userId));
    addIfPresent(keys, eosKey(nativePlatform, nativeUserId));
    addIfPresent(keys, steamKey(platform, userId));
    addIfPresent(keys, steamKey(nativePlatform, nativeUserId));
    addIfPresent(keys, platformKey(platform, userId));
    return keys;
  }

  private static String eosKey(String platform, String userId) {
    if (isBlank(userId)) {
      return null;
    }
    if (matchesPlatform(platform, "EOS") || userId.startsWith("EOS_")) {
      return "EOS:" + stripPrefix(userId, "EOS_");
    }
    return null;
  }

  private static String steamKey(String platform, String userId) {
    if (isBlank(userId)) {
      return null;
    }
    if (matchesPlatform(platform, "Steam") || userId.startsWith("Steam_")) {
      return "Steam:" + stripPrefix(userId, "Steam_");
    }
    return null;
  }

  private static String platformKey(String platform, String userId) {
    if (isBlank(platform) || isBlank(userId)) {
      return null;
    }
    return platform + ":" + userId;
  }

  private static boolean matchesPlatform(String platform, String expected) {
    return platform != null && platform.equalsIgnoreCase(expected);
  }

  private static void addIfPresent(List<String> keys, String key) {
    if (key != null && !keys.contains(key)) {
      keys.add(key);
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String stripPrefix(String value, String prefix) {
    return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
  }
}
