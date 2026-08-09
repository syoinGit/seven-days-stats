package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import com.yuki.sevendays_states.entity.M_Block;
import com.yuki.sevendays_states.entity.M_GameConfigElement;
import com.yuki.sevendays_states.entity.M_GameEntity;
import com.yuki.sevendays_states.entity.M_GameSave;
import com.yuki.sevendays_states.entity.M_Item;
import com.yuki.sevendays_states.entity.M_JapaneseTranslation;
import com.yuki.sevendays_states.entity.M_Player;
import com.yuki.sevendays_states.entity.M_ServerConfigSetting;
import com.yuki.sevendays_states.entity.M_Vehicle;
import com.yuki.sevendays_states.entity.M_World;
import com.yuki.sevendays_states.entity.M_WorldPoi;
import com.yuki.sevendays_states.entity.M_WorldSpawnPoint;
import com.yuki.sevendays_states.entity.T_ImportRun;
import com.yuki.sevendays_states.entity.T_PlayerMarkerSnapshot;
import com.yuki.sevendays_states.entity.T_PlayerStateSnapshot;
import com.yuki.sevendays_states.repository.M_BlockRepository;
import com.yuki.sevendays_states.repository.M_GameConfigElementRepository;
import com.yuki.sevendays_states.repository.M_GameEntityRepository;
import com.yuki.sevendays_states.repository.M_GameSaveRepository;
import com.yuki.sevendays_states.repository.M_ItemRepository;
import com.yuki.sevendays_states.repository.M_JapaneseTranslationRepository;
import com.yuki.sevendays_states.repository.M_PlayerRepository;
import com.yuki.sevendays_states.repository.M_ServerConfigSettingRepository;
import com.yuki.sevendays_states.repository.M_VehicleRepository;
import com.yuki.sevendays_states.repository.M_WorldPoiRepository;
import com.yuki.sevendays_states.repository.M_WorldRepository;
import com.yuki.sevendays_states.repository.M_WorldSpawnPointRepository;
import com.yuki.sevendays_states.repository.T_ImportRunRepository;
import com.yuki.sevendays_states.repository.T_PlayerMarkerSnapshotRepository;
import com.yuki.sevendays_states.repository.T_PlayerStateSnapshotRepository;
import com.yuki.sevendays_states.util.Hashing;
import com.yuki.sevendays_states.util.PlayerIdentity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Slf4j
@Service
@RequiredArgsConstructor
public class SevenDaysDataImportService {

  private static final DateTimeFormatter PLAYER_LOGIN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int FLUSH_INTERVAL = 500;

  private final SevenDaysDataProperties properties;
  private final T_ImportRunRepository importRunRepository;
  private final M_ServerConfigSettingRepository serverConfigSettingRepository;
  private final M_GameConfigElementRepository gameConfigElementRepository;
  private final M_JapaneseTranslationRepository japaneseTranslationRepository;
  private final M_GameEntityRepository gameEntityRepository;
  private final M_BlockRepository blockRepository;
  private final M_ItemRepository itemRepository;
  private final M_VehicleRepository vehicleRepository;
  private final M_WorldRepository worldRepository;
  private final M_GameSaveRepository gameSaveRepository;
  private final M_WorldPoiRepository worldPoiRepository;
  private final M_WorldSpawnPointRepository worldSpawnPointRepository;
  private final M_PlayerRepository playerRepository;
  private final PlayerLookupService playerLookupService;
  private final T_PlayerStateSnapshotRepository playerStateSnapshotRepository;
  private final T_PlayerMarkerSnapshotRepository playerMarkerSnapshotRepository;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @PersistenceContext
  private EntityManager entityManager;

  @Transactional
  public SevenDaysDataImportResult importCurrentData() {
    if (!running.compareAndSet(false, true)) {
      log.info("7DTD data import skipped because another import is running.");
      return emptyResult();
    }
    T_ImportRun importRun = startRun();
    Counter counter = new Counter();
    try {
      importServerConfig(counter);
      importGameConfigs(counter);
      importJapaneseTranslations(counter);
      importWorlds(counter);
      importSaves(importRun, counter);
      finishRun(importRun, "SUCCESS", null);
      SevenDaysDataImportResult result = counter.toResult();
      log.info("7DTD data import completed. {}", result);
      return result;
    } catch (RuntimeException e) {
      finishRun(importRun, "FAILED", e.getMessage());
      throw e;
    } finally {
      running.set(false);
    }
  }

  private T_ImportRun startRun() {
    T_ImportRun run = new T_ImportRun();
    run.setStartedAt(LocalDateTime.now());
    run.setEnvironmentName(properties.environmentName());
    run.setSourceRoot(properties.root().toString());
    run.setConfigDir(properties.configPath().toString());
    run.setDataDir(properties.dataPath().toString());
    run.setGameDir(properties.gamePath().toString());
    run.setStatus("RUNNING");
    return importRunRepository.save(run);
  }

  private void finishRun(T_ImportRun run, String status, String message) {
    run.setFinishedAt(LocalDateTime.now());
    run.setStatus(status);
    run.setMessage(message);
    importRunRepository.save(run);
  }

  private void importServerConfig(Counter counter) {
    Path path = properties.serverConfigPath();
    if (!Files.isRegularFile(path)) {
      return;
    }
    SourceReference source = sourceReference(properties.configPath(), "CONFIG", path);
    Document document = loadXml(path);
    NodeList properties = document.getElementsByTagName("property");
    for (int i = 0; i < properties.getLength(); i++) {
      Element property = (Element) properties.item(i);
      String key = blankToNull(property.getAttribute("name"));
      if (key == null) {
        continue;
      }
      M_ServerConfigSetting setting = serverConfigSettingRepository.findBySettingKey(key)
          .orElseGet(M_ServerConfigSetting::new);
      setting.setSourcePath(source.relativePath());
      setting.setSettingKey(key);
      setting.setSettingValue(property.getAttribute("value"));
      setting.setSensitive(isSensitiveSetting(key));
      serverConfigSettingRepository.save(setting);
      counter.serverSettings++;
    }
  }

  private void importGameConfigs(Counter counter) {
    Path configRoot = properties.gameConfigPath();
    if (!Files.isDirectory(configRoot)) {
      return;
    }
    try (Stream<Path> files = Files.list(configRoot)) {
      files.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".xml"))
          .sorted()
          .forEach(path -> importGameConfigXml(configRoot, path, counter));
    } catch (Exception e) {
      throw new IllegalStateException("game config files cannot be imported: " + configRoot, e);
    }
  }

  private void importGameConfigXml(Path configRoot, Path path, Counter counter) {
    SourceReference source = sourceReference(configRoot, "GAME", path);
    String configName = stripExtension(path.getFileName().toString());
    Document document = loadXml(path);
    Element root = document.getDocumentElement();
    for (Element element : childElements(root)) {
      String key = firstNonBlank(
          element.getAttribute("name"),
          element.getAttribute("id"),
          element.getAttribute("class"),
          element.getNodeName() + ":" + childIndex(element));
      String rawXml = xmlOf(element);
      String sourceHash = Hashing.sha256("game-config|" + configName + "|" + key + "|" + rawXml);
      if (!gameConfigElementRepository.existsBySourceHash(sourceHash)) {
        M_GameConfigElement row = new M_GameConfigElement();
        row.setSourcePath(source.relativePath());
        row.setConfigName(configName);
        row.setElementName(element.getNodeName());
        row.setEntityKey(key);
        row.setExtendsKey(blankToNull(element.getAttribute("extends")));
        row.setDisplayNameKey(firstNonBlank(property(element, "DisplayName"), property(element, "DisplayType"), key));
        row.setCategory(firstNonBlank(property(element, "Class"), property(element, "Group"), property(element, "FilterTags")));
        row.setSourceHash(sourceHash);
        row.setRawXml(rawXml);
        gameConfigElementRepository.save(row);
        counter.gameConfigElements++;
      }
      importSpecializedGameConfig(source, configName, element, rawXml, counter);
      flush(counter.gameConfigElements + counter.gameEntities + counter.blocks + counter.items + counter.vehicles);
    }
  }

  private void importSpecializedGameConfig(
      SourceReference source,
      String configName,
      Element element,
      String rawXml,
      Counter counter) {
    if ("entityclasses".equals(configName) && "entity_class".equals(element.getNodeName())) {
      importGameEntity(source, element, rawXml, counter);
      return;
    }
    if ("blocks".equals(configName) && "block".equals(element.getNodeName())) {
      importBlock(source, element, rawXml, counter);
      return;
    }
    if ("items".equals(configName) && "item".equals(element.getNodeName())) {
      importItem(source, element, rawXml, counter);
      return;
    }
    if ("vehicles".equals(configName) && "vehicle".equals(element.getNodeName())) {
      importVehicle(source, element, rawXml, counter);
    }
  }

  private void importGameEntity(SourceReference source, Element element, String rawXml, Counter counter) {
    String key = blankToNull(element.getAttribute("name"));
    if (key == null) {
      return;
    }
    M_GameEntity entity = gameEntityRepository.findByEntityKey(key).orElseGet(M_GameEntity::new);
    entity.setSourcePath(source.relativePath());
    entity.setEntityKey(key);
    entity.setEntityType(property(element, "Class"));
    entity.setDisplayNameKey(firstNonBlank(property(element, "DisplayName"), key));
    entity.setCategory(entityCategory(key, property(element, "Tags")));
    entity.setTags(property(element, "Tags"));
    entity.setRawXml(rawXml);
    gameEntityRepository.save(entity);
    counter.gameEntities++;
  }

  private void importBlock(SourceReference source, Element element, String rawXml, Counter counter) {
    String key = blankToNull(element.getAttribute("name"));
    if (key == null) {
      return;
    }
    M_Block block = blockRepository.findByBlockKey(key).orElseGet(M_Block::new);
    block.setSourcePath(source.relativePath());
    block.setBlockKey(key);
    block.setDisplayNameKey(firstNonBlank(property(element, "DisplayName"), key));
    block.setMaterial(property(element, "Material"));
    block.setShape(property(element, "Shape"));
    block.setCategory(blockCategory(key, property(element, "FilterTags"), property(element, "Tags")));
    block.setTags(firstNonBlank(property(element, "Tags"), property(element, "FilterTags")));
    block.setRawXml(rawXml);
    blockRepository.save(block);
    counter.blocks++;
  }

  private void importItem(SourceReference source, Element element, String rawXml, Counter counter) {
    String key = blankToNull(element.getAttribute("name"));
    if (key == null) {
      return;
    }
    M_Item item = itemRepository.findByItemKey(key).orElseGet(M_Item::new);
    item.setSourcePath(source.relativePath());
    item.setItemKey(key);
    item.setItemType(property(element, "Class"));
    item.setDisplayNameKey(firstNonBlank(property(element, "DisplayName"), key));
    item.setCategory(itemCategory(key, property(element, "Tags")));
    item.setTags(property(element, "Tags"));
    item.setRawXml(rawXml);
    itemRepository.save(item);
    counter.items++;
  }

  private void importVehicle(SourceReference source, Element element, String rawXml, Counter counter) {
    String key = blankToNull(element.getAttribute("name"));
    if (key == null) {
      return;
    }
    M_Vehicle vehicle = vehicleRepository.findByVehicleKey(key).orElseGet(M_Vehicle::new);
    vehicle.setSourcePath(source.relativePath());
    vehicle.setVehicleKey(key);
    vehicle.setEntityClassKey(property(element, "entity_class"));
    vehicle.setDisplayNameKey(firstNonBlank(property(element, "DisplayName"), key));
    vehicle.setVehicleType(vehicleCategory(key));
    vehicle.setRawXml(rawXml);
    vehicleRepository.save(vehicle);
    counter.vehicles++;
  }

  private void importJapaneseTranslations(Counter counter) {
    Path path = properties.gameConfigPath().resolve("Localization.csv");
    if (!Files.isRegularFile(path)) {
      return;
    }
    SourceReference source = sourceReference(properties.gameConfigPath(), "GAME", path);
    try {
      List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      if (lines.size() < 2) {
        return;
      }
      List<String> header = parseCsvLine(lines.get(0));
      int keyIndex = header.indexOf("Key");
      int fileIndex = header.indexOf("File");
      int typeIndex = header.indexOf("Type");
      int englishIndex = header.indexOf("english");
      int contextIndex = header.indexOf("Context / Alternate Text");
      int japaneseIndex = header.indexOf("japanese");
      for (int i = 1; i < lines.size(); i++) {
        List<String> row = parseCsvLine(lines.get(i));
        String key = value(row, keyIndex);
        if (key == null || key.isBlank()) {
          continue;
        }
        String japanese = value(row, japaneseIndex);
        String english = value(row, englishIndex);
        M_JapaneseTranslation entry = japaneseTranslationRepository.findByLocalizationKey(key)
            .orElseGet(M_JapaneseTranslation::new);
        entry.setSourcePath(source.relativePath());
        entry.setLocalizationKey(key);
        entry.setSource(value(row, fileIndex));
        entry.setEntryType(value(row, typeIndex));
        entry.setContext(value(row, contextIndex));
        entry.setEnglish(english);
        entry.setJapanese(japanese);
        entry.setDisplayText(firstNonBlank(japanese, english, key));
        entry.setTranslated(japanese != null && !japanese.isBlank());
        japaneseTranslationRepository.save(entry);
        counter.japaneseTranslations++;
        flush(counter.japaneseTranslations);
      }
    } catch (Exception e) {
      throw new IllegalStateException("japanese translations cannot be imported: " + path, e);
    }
  }

  private void importWorlds(Counter counter) {
    Path worldsRoot = properties.generatedWorldsPath();
    if (!Files.isDirectory(worldsRoot)) {
      return;
    }
    try (Stream<Path> worlds = Files.list(worldsRoot)) {
      worlds.filter(Files::isDirectory)
          .sorted()
          .forEach(worldPath -> importWorld(worldsRoot, worldPath, counter));
    } catch (Exception e) {
      throw new IllegalStateException("worlds cannot be imported: " + worldsRoot, e);
    }
  }

  private void importWorld(Path worldsRoot, Path worldPath, Counter counter) {
    String worldName = worldPath.getFileName().toString();
    importWorldInfo(worldsRoot, worldPath.resolve("map_info.xml"), worldName, counter);
    importWorldPois(worldsRoot, worldPath.resolve("prefabs.xml"), worldName, null, counter);
    importWorldSpawnPoints(worldsRoot, worldPath.resolve("spawnpoints.xml"), worldName, counter);
  }

  private void importWorldInfo(Path worldsRoot, Path path, String worldName, Counter counter) {
    if (!Files.isRegularFile(path)) {
      return;
    }
    SourceReference source = sourceReference(worldsRoot, "DATA", path);
    Document document = loadXml(path);
    Element root = document.getDocumentElement();
    M_World world = worldRepository.findByWorldName(worldName).orElseGet(M_World::new);
    world.setSourcePath(source.relativePath());
    world.setWorldName(worldName);
    world.setHeightMapSize(parseHeightMapSize(property(root, "HeightMapSize")));
    world.setGenerationSeed(firstNonBlank(property(root, "Seed"), nestedGenerationSeed(root)));
    world.setRawXml(xmlOf(root));
    worldRepository.save(world);
    counter.worlds++;
  }

  private void importWorldPois(
      Path worldsRoot,
      Path path,
      String worldName,
      String gameName,
      Counter counter) {
    if (!Files.isRegularFile(path)) {
      return;
    }
    SourceReference source = sourceReference(worldsRoot, "DATA", path);
    Document document = loadXml(path);
    NodeList decorations = document.getElementsByTagName("decoration");
    for (int i = 0; i < decorations.getLength(); i++) {
      Element decoration = (Element) decorations.item(i);
      int[] position = parseIntPosition(decoration.getAttribute("position"));
      if (position == null || position.length < 3) {
        continue;
      }
      String name = decoration.getAttribute("name");
      String hash = Hashing.sha256("poi|" + worldName + "|" + nullToBlank(gameName) + "|" + name + "|"
          + decoration.getAttribute("position") + "|" + decoration.getAttribute("rotation"));
      if (worldPoiRepository.existsBySourceHash(hash)) {
        continue;
      }
      M_WorldPoi poi = new M_WorldPoi();
      poi.setSourcePath(source.relativePath());
      poi.setSourceHash(hash);
      poi.setWorldName(worldName);
      poi.setGameName(gameName);
      poi.setPoiName(name);
      poi.setPoiType(blankToNull(decoration.getAttribute("type")));
      poi.setCategory(categoryOf(name));
      poi.setX(position[0]);
      poi.setY(position[1]);
      poi.setZ(position[2]);
      poi.setRotation(parseInteger(decoration.getAttribute("rotation")));
      worldPoiRepository.save(poi);
      counter.worldPois++;
    }
  }

  private void importWorldSpawnPoints(Path worldsRoot, Path path, String worldName, Counter counter) {
    if (!Files.isRegularFile(path)) {
      return;
    }
    SourceReference source = sourceReference(worldsRoot, "DATA", path);
    Document document = loadXml(path);
    NodeList spawnpoints = document.getElementsByTagName("spawnpoint");
    for (int i = 0; i < spawnpoints.getLength(); i++) {
      Element spawnpoint = (Element) spawnpoints.item(i);
      double[] position = parseDoublePosition(spawnpoint.getAttribute("position"));
      double[] rotation = parseDoublePosition(spawnpoint.getAttribute("rotation"));
      if (position == null || position.length < 3) {
        continue;
      }
      String hash = Hashing.sha256("spawn|" + worldName + "|" + spawnpoint.getAttribute("position") + "|"
          + spawnpoint.getAttribute("rotation"));
      if (worldSpawnPointRepository.existsBySourceHash(hash)) {
        continue;
      }
      M_WorldSpawnPoint point = new M_WorldSpawnPoint();
      point.setSourcePath(source.relativePath());
      point.setSourceHash(hash);
      point.setWorldName(worldName);
      point.setX(position[0]);
      point.setY(position[1]);
      point.setZ(position[2]);
      point.setRotationX(rotation == null ? null : rotation[0]);
      point.setRotationY(rotation == null ? null : rotation[1]);
      point.setRotationZ(rotation == null ? null : rotation[2]);
      worldSpawnPointRepository.save(point);
      counter.worldSpawnPoints++;
    }
  }

  private void importSaves(T_ImportRun importRun, Counter counter) {
    Path savesRoot = properties.savesPath();
    if (!Files.isDirectory(savesRoot)) {
      return;
    }
    try (Stream<Path> worlds = Files.list(savesRoot)) {
      worlds.filter(Files::isDirectory)
          .sorted()
          .forEach(worldDir -> importSaveWorld(importRun, savesRoot, worldDir, counter));
    } catch (Exception e) {
      throw new IllegalStateException("saves cannot be imported: " + savesRoot, e);
    }
  }

  private void importSaveWorld(T_ImportRun importRun, Path savesRoot, Path worldDir, Counter counter) {
    try (Stream<Path> games = Files.list(worldDir)) {
      games.filter(Files::isDirectory)
          .sorted()
          .forEach(gameDir -> importSaveGame(importRun, savesRoot, worldDir, gameDir, counter));
    } catch (Exception e) {
      throw new IllegalStateException("save world cannot be imported: " + worldDir, e);
    }
  }

  private void importSaveGame(T_ImportRun importRun, Path savesRoot, Path worldDir, Path gameDir, Counter counter) {
    String worldName = worldDir.getFileName().toString();
    String gameName = gameDir.getFileName().toString();
    SourceReference saveDirectorySource = sourceReferenceForDirectory(savesRoot, "DATA", gameDir);
    M_GameSave save = gameSaveRepository.findByWorldNameAndGameName(worldName, gameName).orElseGet(M_GameSave::new);
    save.setSourcePath(saveDirectorySource.relativePath());
    save.setWorldName(worldName);
    save.setGameName(gameName);
    save.setSavePath(savesRoot.relativize(gameDir).toString());
    save.setLastScannedAt(LocalDateTime.now());
    save.setSourceHash(saveDirectorySource.sourceHash());
    gameSaveRepository.save(save);
    counter.gameSaves++;

    importPlayersXml(importRun, savesRoot, gameDir.resolve("players.xml"), worldName, gameName, counter);
  }

  private void importPlayersXml(
      T_ImportRun importRun,
      Path savesRoot,
      Path path,
      String worldName,
      String gameName,
      Counter counter) {
    if (!Files.isRegularFile(path)) {
      return;
    }
    SourceReference source = sourceReference(savesRoot, "DATA", path);
    Document document = loadXml(path);
    NodeList players = document.getElementsByTagName("player");
    LocalDateTime capturedAt = source.lastModifiedAt() == null ? LocalDateTime.now() : source.lastModifiedAt();
    for (int i = 0; i < players.getLength(); i++) {
      Element element = (Element) players.item(i);
      M_Player player = upsertPlayer(source, element, capturedAt, counter);
      savePlayerStateSnapshot(importRun, source, player, element, worldName, gameName, capturedAt, counter);
      savePlayerMarkers(importRun, source, player, element, worldName, gameName, capturedAt, counter);
    }
  }

  private M_Player upsertPlayer(SourceReference source, Element element, LocalDateTime capturedAt, Counter counter) {
    String platform = required(element, "platform");
    String userId = required(element, "userid");
    String nativePlatform = blankToNull(element.getAttribute("nativeplatform"));
    String nativeUserId = blankToNull(element.getAttribute("nativeuserid"));
    String playerKey = PlayerIdentity.canonicalPlayerKey(platform, userId, nativePlatform, nativeUserId);
    if (playerKey == null) {
      playerKey = platform + ":" + userId;
    }
    M_Player player = playerLookupService.findExisting(
        playerKey, platform, userId, nativePlatform, nativeUserId)
        .orElseGet(M_Player::new);
    boolean created = player.getId() == null;
    player.setSourcePath(source.relativePath());
    player.setPlayerKey(playerKey);
    player.setPlatform(platform);
    player.setUserId(userId);
    player.setNativePlatform(nativePlatform);
    player.setNativeUserId(nativeUserId);
    player.setPlayerName(required(element, "playername"));
    if (created) {
      player.setFirstSeenAt(capturedAt);
    }
    player.setLastSeenAt(capturedAt);
    M_Player saved = playerRepository.save(player);
    counter.players++;
    return saved;
  }

  private void savePlayerStateSnapshot(
      T_ImportRun importRun,
      SourceReference source,
      M_Player player,
      Element element,
      String worldName,
      String gameName,
      LocalDateTime capturedAt,
      Counter counter) {
    String hash = Hashing.sha256("player-state|" + source.relativePath() + "|" + player.getPlayerKey() + "|"
        + worldName + "|" + gameName + "|" + element.getAttribute("lastlogin") + "|"
        + element.getAttribute("position"));
    if (playerStateSnapshotRepository.existsBySourceHash(hash)) {
      return;
    }
    int[] position = parseIntPosition(element.getAttribute("position"));
    T_PlayerStateSnapshot snapshot = new T_PlayerStateSnapshot();
    snapshot.setImportRun(importRun);
    snapshot.setSourcePath(source.relativePath());
    snapshot.setPlayer(player);
    snapshot.setWorldName(worldName);
    snapshot.setGameName(gameName);
    snapshot.setCapturedAt(capturedAt);
    snapshot.setPlayGroup(blankToNull(element.getAttribute("playgroup")));
    snapshot.setLastLogin(parseDateTime(element.getAttribute("lastlogin")));
    if (position != null && position.length >= 3) {
      snapshot.setX(position[0]);
      snapshot.setY(position[1]);
      snapshot.setZ(position[2]);
    }
    snapshot.setSourceHash(hash);
    playerStateSnapshotRepository.save(snapshot);
    counter.playerStateSnapshots++;
  }

  private void savePlayerMarkers(
      T_ImportRun importRun,
      SourceReference source,
      M_Player player,
      Element playerElement,
      String worldName,
      String gameName,
      LocalDateTime capturedAt,
      Counter counter) {
    saveChildMarkers(importRun, source, player, playerElement, worldName, gameName, capturedAt, "acl", "ACL", false, counter);
    saveChildMarkers(importRun, source, player, playerElement, worldName, gameName, capturedAt, "lpblock", "LAND_CLAIM", true, counter);
    saveChildMarkers(importRun, source, player, playerElement, worldName, gameName, capturedAt, "bedroll", "BEDROLL", true, counter);
    saveChildMarkers(importRun, source, player, playerElement, worldName, gameName, capturedAt, "backpack", "BACKPACK", true, counter);
    saveNestedPositionMarkers(importRun, source, player, playerElement, worldName, gameName, capturedAt, "questpositions", "QUEST_POSITION", counter);
    saveNestedPositionMarkers(importRun, source, player, playerElement, worldName, gameName, capturedAt, "vendingmachinepositions", "VENDING_MACHINE", counter);
  }

  private void saveChildMarkers(
      T_ImportRun importRun,
      SourceReference source,
      M_Player player,
      Element playerElement,
      String worldName,
      String gameName,
      LocalDateTime capturedAt,
      String tagName,
      String markerType,
      boolean hasPosition,
      Counter counter) {
    NodeList nodes = playerElement.getElementsByTagName(tagName);
    for (int i = 0; i < nodes.getLength(); i++) {
      Element marker = (Element) nodes.item(i);
      T_PlayerMarkerSnapshot snapshot = markerSnapshot(importRun, source, player, worldName, gameName, capturedAt, markerType);
      snapshot.setTargetPlatform(blankToNull(marker.getAttribute("platform")));
      snapshot.setTargetUserId(blankToNull(marker.getAttribute("userid")));
      snapshot.setRefId(blankToNull(marker.getAttribute("id")));
      if (hasPosition) {
        applyPosition(snapshot, firstNonBlank(marker.getAttribute("pos"), marker.getAttribute("position")));
      }
      saveMarkerSnapshot(snapshot, source.sourceHash(), player.getPlayerKey() + "|" + markerType + "|" + i + "|" + xmlOf(marker), counter);
    }
  }

  private void saveNestedPositionMarkers(
      T_ImportRun importRun,
      SourceReference source,
      M_Player player,
      Element playerElement,
      String worldName,
      String gameName,
      LocalDateTime capturedAt,
      String parentTagName,
      String markerType,
      Counter counter) {
    NodeList parents = playerElement.getElementsByTagName(parentTagName);
    for (int p = 0; p < parents.getLength(); p++) {
      Element parent = (Element) parents.item(p);
      NodeList positions = parent.getElementsByTagName("position");
      for (int i = 0; i < positions.getLength(); i++) {
        Element position = (Element) positions.item(i);
        T_PlayerMarkerSnapshot snapshot = markerSnapshot(importRun, source, player, worldName, gameName, capturedAt, markerType);
        snapshot.setRefId(blankToNull(position.getAttribute("id")));
        snapshot.setPositionDataType(blankToNull(position.getAttribute("positiondatatype")));
        applyPosition(snapshot, firstNonBlank(position.getAttribute("pos"), position.getAttribute("position")));
        saveMarkerSnapshot(snapshot, source.sourceHash(), player.getPlayerKey() + "|" + markerType + "|" + p + "|" + i + "|" + xmlOf(position), counter);
      }
    }
  }

  private T_PlayerMarkerSnapshot markerSnapshot(
      T_ImportRun importRun,
      SourceReference source,
      M_Player player,
      String worldName,
      String gameName,
      LocalDateTime capturedAt,
      String markerType) {
    T_PlayerMarkerSnapshot snapshot = new T_PlayerMarkerSnapshot();
    snapshot.setImportRun(importRun);
    snapshot.setSourcePath(source.relativePath());
    snapshot.setPlayer(player);
    snapshot.setWorldName(worldName);
    snapshot.setGameName(gameName);
    snapshot.setCapturedAt(capturedAt);
    snapshot.setMarkerType(markerType);
    return snapshot;
  }

  private void saveMarkerSnapshot(T_PlayerMarkerSnapshot snapshot, String sourceHash, String key, Counter counter) {
    String hash = Hashing.sha256("player-marker|" + sourceHash + "|" + key);
    if (playerMarkerSnapshotRepository.existsBySourceHash(hash)) {
      return;
    }
    snapshot.setSourceHash(hash);
    playerMarkerSnapshotRepository.save(snapshot);
    counter.playerMarkerSnapshots++;
  }

  private SourceReference sourceReference(Path root, String sourceArea, Path path) {
    try {
      String relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString();
      long size = Files.size(path);
      Instant modified = Files.getLastModifiedTime(path).toInstant();
      String hash = Hashing.sha256(sourceArea + "|" + relative + "|" + size + "|" + modified.toEpochMilli());
      return new SourceReference(sourceArea, relative, path.toAbsolutePath().normalize(), size, toLocalDateTime(modified), hash);
    } catch (Exception e) {
      throw new IllegalStateException("source file cannot be read: " + path, e);
    }
  }

  private SourceReference sourceReferenceForDirectory(Path root, String sourceArea, Path path) {
    try {
      String relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString();
      Instant modified = Files.getLastModifiedTime(path).toInstant();
      String hash = Hashing.sha256("directory|" + sourceArea + "|" + relative + "|" + modified.toEpochMilli());
      return new SourceReference(sourceArea, relative, path.toAbsolutePath().normalize(), 0L, toLocalDateTime(modified), hash);
    } catch (Exception e) {
      throw new IllegalStateException("source directory cannot be read: " + path, e);
    }
  }

  private Document loadXml(Path path) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Document document = factory.newDocumentBuilder().parse(path.toFile());
      document.getDocumentElement().normalize();
      return document;
    } catch (Exception e) {
      throw new IllegalStateException("XML cannot be loaded: " + path, e);
    }
  }

  private String xmlOf(Node node) {
    try {
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      transformer.setOutputProperty(OutputKeys.INDENT, "no");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(node), new StreamResult(writer));
      return writer.toString();
    } catch (Exception e) {
      throw new IllegalStateException("XML node cannot be serialized", e);
    }
  }

  private List<Element> childElements(Element element) {
    List<Element> result = new ArrayList<>();
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element child) {
        result.add(child);
      }
    }
    return result;
  }

  private String property(Element element, String name) {
    NodeList properties = element.getElementsByTagName("property");
    for (int i = 0; i < properties.getLength(); i++) {
      Element property = (Element) properties.item(i);
      if (name.equals(property.getAttribute("name")) || name.equals(property.getAttribute("class"))) {
        return blankToNull(property.getAttribute("value"));
      }
    }
    return null;
  }

  private String nestedGenerationSeed(Element root) {
    NodeList properties = root.getElementsByTagName("property");
    for (int i = 0; i < properties.getLength(); i++) {
      Element property = (Element) properties.item(i);
      if ("Generation".equals(property.getAttribute("class"))) {
        return property(property, "Seed");
      }
    }
    return null;
  }

  private List<String> parseCsvLine(String line) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (ch == ',' && !quoted) {
        values.add(current.toString());
        current.setLength(0);
      } else {
        current.append(ch);
      }
    }
    values.add(current.toString());
    return values;
  }

  private void applyPosition(T_PlayerMarkerSnapshot marker, String rawPosition) {
    int[] position = parseIntPosition(rawPosition);
    if (position == null || position.length < 3) {
      return;
    }
    marker.setX(position[0]);
    marker.setY(position[1]);
    marker.setZ(position[2]);
  }

  private int[] parseIntPosition(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String[] parts = value.split(",");
    int[] values = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      values[i] = (int) Math.round(Double.parseDouble(parts[i].trim()));
    }
    return values;
  }

  private double[] parseDoublePosition(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String[] parts = value.split(",");
    double[] values = new double[parts.length];
    for (int i = 0; i < parts.length; i++) {
      values[i] = Double.parseDouble(parts[i].trim());
    }
    return values;
  }

  private Integer parseHeightMapSize(String value) {
    int[] position = parseIntPosition(value);
    return position == null || position.length == 0 ? null : position[0];
  }

  private Integer parseInteger(String value) {
    try {
      return value == null || value.isBlank() ? null : Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private LocalDateTime parseDateTime(String value) {
    return value == null || value.isBlank() ? null : LocalDateTime.parse(value, PLAYER_LOGIN_TIME);
  }

  private String required(Element element, String attributeName) {
    String value = blankToNull(element.getAttribute(attributeName));
    if (value == null) {
      throw new IllegalArgumentException("required attribute is missing: " + attributeName);
    }
    return value;
  }

  private String stripExtension(String fileName) {
    int index = fileName.lastIndexOf('.');
    return index < 0 ? fileName : fileName.substring(0, index);
  }

  private String categoryOf(String name) {
    int separator = name == null ? -1 : name.indexOf('_');
    return separator < 0 ? name : name.substring(0, separator);
  }

  private String entityCategory(String key, String tags) {
    String normalized = (key + "," + nullToBlank(tags)).toLowerCase();
    if (normalized.contains("zombie")) {
      return "zombie";
    }
    if (normalized.contains("animal")) {
      return "animal";
    }
    if (normalized.contains("player")) {
      return "player";
    }
    return "entity";
  }

  private String blockCategory(String key, String filterTags, String tags) {
    String normalized = (key + "," + nullToBlank(filterTags) + "," + nullToBlank(tags)).toLowerCase();
    if (normalized.contains("terrain")) {
      return "terrain";
    }
    if (normalized.contains("playerblocks")) {
      return "player_block";
    }
    if (normalized.contains("building")) {
      return "building";
    }
    if (normalized.contains("trap")) {
      return "trap";
    }
    if (normalized.contains("loot")) {
      return "loot";
    }
    if (normalized.contains("outdoor")) {
      return "outdoor";
    }
    return "block";
  }

  private String itemCategory(String key, String tags) {
    String normalized = (key + "," + nullToBlank(tags)).toLowerCase();
    if (normalized.contains("melee")) {
      return "melee";
    }
    if (normalized.contains("ranged") || normalized.contains("gun")) {
      return "ranged";
    }
    if (normalized.contains("vehicle")) {
      return "vehicle";
    }
    if (normalized.contains("food")) {
      return "food";
    }
    return "item";
  }

  private String vehicleCategory(String key) {
    String normalized = key.toLowerCase();
    if (normalized.contains("gyro")) {
      return "air";
    }
    if (normalized.contains("bicycle")) {
      return "bicycle";
    }
    return "ground";
  }

  private boolean isSensitiveSetting(String key) {
    String lower = key == null ? "" : key.toLowerCase();
    return lower.contains("password") || lower.contains("token") || lower.contains("secret");
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String nullToBlank(String value) {
    return value == null ? "" : value;
  }

  private String value(List<String> row, int index) {
    return index < 0 || index >= row.size() ? null : row.get(index);
  }

  private int childIndex(Element element) {
    int index = 0;
    Node previous = element.getPreviousSibling();
    while (previous != null) {
      if (previous instanceof Element) {
        index++;
      }
      previous = previous.getPreviousSibling();
    }
    return index;
  }

  private LocalDateTime toLocalDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
  }

  private void flush(long value) {
    if (value > 0 && value % FLUSH_INTERVAL == 0) {
      entityManager.flush();
      entityManager.clear();
    }
  }

  private SevenDaysDataImportResult emptyResult() {
    return new Counter().toResult();
  }

  private record SourceReference(
      String sourceArea,
      String relativePath,
      Path absolutePath,
      Long sizeBytes,
      LocalDateTime lastModifiedAt,
      String sourceHash) {
  }

  private static class Counter {
    private long serverSettings;
    private long gameConfigElements;
    private long japaneseTranslations;
    private long gameEntities;
    private long blocks;
    private long items;
    private long vehicles;
    private long worlds;
    private long gameSaves;
    private long worldPois;
    private long worldSpawnPoints;
    private long players;
    private long playerStateSnapshots;
    private long playerMarkerSnapshots;

    private SevenDaysDataImportResult toResult() {
      return new SevenDaysDataImportResult(
          serverSettings,
          gameConfigElements,
          japaneseTranslations,
          gameEntities,
          blocks,
          items,
          vehicles,
          worlds,
          gameSaves,
          worldPois,
          worldSpawnPoints,
          players,
          playerStateSnapshots,
          playerMarkerSnapshots);
    }
  }
}
