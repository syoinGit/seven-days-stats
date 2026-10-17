package com.yuki.rubinco.watchpoint.readmodel;

import com.yuki.rubinco.watchpoint.ingest.SevenDaysDataProperties;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PoiNameService {

  private final SevenDaysDataProperties properties;
  private final ResourceLoader resourceLoader;
  private final Map<String, String> translations = new HashMap<>();
  private final Map<String, String> fallbackTokenTranslations = Map.ofEntries(
      Map.entry("countrytown", "田舎町"),
      Map.entry("city", "市街地"),
      Map.entry("downtown", "市街地"),
      Map.entry("residential", "住宅街"),
      Map.entry("commercial", "商業地区"),
      Map.entry("industrial", "工業地区"),
      Map.entry("remnant", "廃墟"),
      Map.entry("business", "事務所"),
      Map.entry("house", "住宅"),
      Map.entry("store", "店舗"),
      Map.entry("grocery", "食料品店"),
      Map.entry("pharmacy", "薬局"),
      Map.entry("hardware", "工具店"),
      Map.entry("book", "本屋"),
      Map.entry("gun", "銃砲店"),
      Map.entry("electronics", "電器店"),
      Map.entry("auto", "自動車店"),
      Map.entry("restaurant", "レストラン"),
      Map.entry("diner", "食堂"),
      Map.entry("gas", "ガソリン"),
      Map.entry("station", "スタンド"),
      Map.entry("church", "教会"),
      Map.entry("school", "学校"),
      Map.entry("hospital", "病院"),
      Map.entry("police", "警察署"),
      Map.entry("fire", "消防署"),
      Map.entry("factory", "工場"),
      Map.entry("warehouse", "倉庫"),
      Map.entry("farm", "農場"),
      Map.entry("camp", "キャンプ地"),
      Map.entry("trader", "トレーダー"),
      Map.entry("garage", "ガレージ"),
      Map.entry("barn", "納屋"),
      Map.entry("cabin", "小屋"),
      Map.entry("hotel", "ホテル"),
      Map.entry("motel", "モーテル"),
      Map.entry("parking", "駐車場"),
      Map.entry("ranger", "森林警備隊"),
      Map.entry("lot", "空き地"),
      Map.entry("base", "基地"),
      Map.entry("military", "軍事"),
      Map.entry("survivor", "生存者"),
      Map.entry("site", "拠点"),
      Map.entry("strip", "商店街"),
      Map.entry("modern", "現代風"),
      Map.entry("old", "旧式"),
      Map.entry("bungalow", "平屋"),
      Map.entry("mansard", "マンサード屋根"),
      Map.entry("modular", "モジュール式"),
      Map.entry("victorian", "ヴィクトリア様式"),
      Map.entry("pyramid", "ピラミッド屋根"),
      Map.entry("tudor", "チューダー様式"),
      Map.entry("ranch", "牧場風"),
      Map.entry("burnt", "焼失"),
      Map.entry("vacant", "更地"),
      Map.entry("rural", "農村"),
      Map.entry("filler", "区画"),
      Map.entry("rwg", "道路生成"),
      Map.entry("tile", "区画"),
      Map.entry("gateway", "入口"),
      Map.entry("corner", "角地"),
      Map.entry("straight", "直線"),
      Map.entry("intersection", "交差点"),
      Map.entry("t", "T字路"),
      Map.entry("countryres", "郊外住宅地"),
      Map.entry("countryresidential", "郊外住宅地"),
      Map.entry("apartments", "集合住宅"),
      Map.entry("cemetery", "墓地"),
      Map.entry("trailer", "トレーラーハウス"),
      Map.entry("celltower", "電波塔"),
      Map.entry("bombshelter", "地下シェルター"),
      Map.entry("bowling", "ボウリング"),
      Map.entry("alley", "場"),
      Map.entry("quarry", "採石場"),
      Map.entry("sawmill", "製材所"),
      Map.entry("mine", "鉱山"),
      Map.entry("water", "給水"),
      Map.entry("tower", "塔"),
      Map.entry("selfstorage", "貸倉庫"),
      Map.entry("coffee", "コーヒー"),
      Map.entry("shop", "店"),
      Map.entry("army", "軍施設"),
      Map.entry("construction", "建設現場"),
      Map.entry("utility", "公共施設"),
      Map.entry("wilderness", "荒野"));
  private final Map<String, String> categoryTranslations = Map.ofEntries(
      Map.entry("apartments", "集合住宅"), Map.entry("base", "基地"),
      Map.entry("barn", "納屋"), Map.entry("bombshelter", "地下シェルター"),
      Map.entry("bowling", "ボウリング場"), Map.entry("business", "事務所"),
      Map.entry("cabin", "小屋"), Map.entry("cemetery", "墓地"),
      Map.entry("church", "教会"), Map.entry("countrytown", "田舎町"),
      Map.entry("diner", "食堂"), Map.entry("farm", "農場"),
      Map.entry("fire", "消防署"), Map.entry("gas", "ガソリンスタンド"),
      Map.entry("house", "住宅"), Map.entry("lot", "空き地"),
      Map.entry("mine", "鉱山"), Map.entry("motel", "モーテル"),
      Map.entry("police", "警察署"), Map.entry("quarry", "採石場"),
      Map.entry("ranger", "森林警備隊"), Map.entry("remnant", "廃墟"),
      Map.entry("rwg", "道路区画"), Map.entry("sawmill", "製材所"),
      Map.entry("school", "学校"), Map.entry("store", "店舗"),
      Map.entry("survivor", "生存者拠点"), Map.entry("trader", "トレーダー"),
      Map.entry("trailer", "トレーラーハウス"), Map.entry("utility", "公共施設"),
      Map.entry("water", "給水施設"));
  private final Set<String> missingKeys = ConcurrentHashMap.newKeySet();

  @PostConstruct
  void loadTranslations() {
    Resource resource = resourceLoader.getResource(properties.poi().translationResource());
    if (!resource.exists()) {
      log.info("POI translation resource was not found. resource={}", properties.poi().translationResource());
      return;
    }
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (lineNumber == 1 && line.startsWith("poi_key,")) {
          continue;
        }
        if (line.isBlank() || line.trim().startsWith("#")) {
          continue;
        }
        String[] columns = line.split(",", 2);
        if (columns.length != 2 || columns[0].isBlank() || columns[1].isBlank()) {
          log.warn("Invalid POI translation row skipped. resource={}, line={}",
              properties.poi().translationResource(), lineNumber);
          continue;
        }
        String key = normalizeKey(columns[0]);
        if (translations.containsKey(key)) {
          log.warn("Duplicate POI translation key ignored. resource={}, line={}, key={}",
              properties.poi().translationResource(), lineNumber, key);
          continue;
        }
        translations.put(key, columns[1].trim());
      }
      log.info("POI translations loaded. resource={}, count={}",
          properties.poi().translationResource(), translations.size());
    } catch (Exception e) {
      log.warn("POI translation resource cannot be loaded. resource={}",
          properties.poi().translationResource(), e);
    }
  }

  public String displayName(String poiName) {
    if (poiName == null || poiName.isBlank()) {
      return "荒野のどこか";
    }
    String key = normalizeKey(poiName);
    String translated = translations.get(key);
    if (translated != null && !translated.isBlank()) {
      return translated;
    }
    if (missingKeys.add(key)) {
      log.info("POI translation is not registered. key={}, raw={}", key, poiName);
    }
    return fallbackDisplayName(key);
  }

  public String displayCategory(String category) {
    if (category == null || category.isBlank()) {
      return "分類不明";
    }
    String normalized = normalizeKey(category);
    return categoryTranslations.getOrDefault(normalized, fallbackDisplayName(normalized));
  }

  public String normalizeKey(String poiName) {
    if (poiName == null) {
      return "";
    }
    return poiName.trim()
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s*#\\s*\\d{1,3}$", "")
        .replaceAll("[\\s_-]+\\d{1,3}$", "")
        .replaceAll("[^a-z0-9]+", "_")
        .replaceAll("_+", "_")
        .replaceAll("^_|_$", "");
  }

  private String fallbackDisplayName(String key) {
    if (key == null || key.isBlank()) {
      return "荒野のどこか";
    }
    String[] tokens = key.replaceAll("_\\d+$", "").split("_");
    StringBuilder displayName = new StringBuilder();
    for (String token : tokens) {
      if (token.isBlank() || token.matches("\\d+")) {
        continue;
      }
      String normalizedToken = token.replaceFirst("(?<=[a-z])\\d+$", "");
      String translated = fallbackTokenTranslations.get(normalizedToken);
      if (translated == null) {
        translated = normalizedToken;
      }
      if (!displayName.isEmpty()) {
        displayName.append(" ");
      }
      displayName.append(translated);
    }
    return displayName.isEmpty() ? key.replace('_', ' ') : displayName.toString();
  }
}
