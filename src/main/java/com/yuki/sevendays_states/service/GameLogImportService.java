package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import com.yuki.sevendays_states.entity.M_Player;
import com.yuki.sevendays_states.entity.T_EntityKillTransaction;
import com.yuki.sevendays_states.entity.T_LevelXpSummaryTransaction;
import com.yuki.sevendays_states.entity.T_PlayerCurrentState;
import com.yuki.sevendays_states.entity.T_PlayerJoinTransaction;
import com.yuki.sevendays_states.entity.T_PlayerLeaveTransaction;
import com.yuki.sevendays_states.entity.T_PlayerPositionTransaction;
import com.yuki.sevendays_states.entity.T_ServerMetric;
import com.yuki.sevendays_states.entity.T_SleeperTransaction;
import com.yuki.sevendays_states.entity.T_VehicleCurrentState;
import com.yuki.sevendays_states.entity.T_VehiclePositionTransaction;
import com.yuki.sevendays_states.entity.T_WorldEventTransaction;
import com.yuki.sevendays_states.entity.TimelinePostType;
import com.yuki.sevendays_states.log.dto.EntityKillLogEvent;
import com.yuki.sevendays_states.log.dto.LevelXpSummaryLogEvent;
import com.yuki.sevendays_states.log.dto.ParsedLogLine;
import com.yuki.sevendays_states.log.dto.PlayerJoinLogEvent;
import com.yuki.sevendays_states.log.dto.PlayerLeaveLogEvent;
import com.yuki.sevendays_states.log.dto.PlayerListPositionLogEvent;
import com.yuki.sevendays_states.log.dto.ServerMetricLogEvent;
import com.yuki.sevendays_states.log.dto.SleeperLogEvent;
import com.yuki.sevendays_states.log.dto.VehicleLogEvent;
import com.yuki.sevendays_states.log.dto.WorldEventLogEvent;
import com.yuki.sevendays_states.log.parser.AiDirectorLogParser;
import com.yuki.sevendays_states.log.parser.AirDropLogParser;
import com.yuki.sevendays_states.log.parser.BloodMoonLogParser;
import com.yuki.sevendays_states.log.parser.EntityKillLogParser;
import com.yuki.sevendays_states.log.parser.GameLogLineParser;
import com.yuki.sevendays_states.log.parser.LevelXpSummaryLogParser;
import com.yuki.sevendays_states.log.parser.PlayerChatCommandParser;
import com.yuki.sevendays_states.log.parser.PlayerDeathLogParser;
import com.yuki.sevendays_states.log.parser.PlayerJoinLogParser;
import com.yuki.sevendays_states.log.parser.PlayerLeaveLogParser;
import com.yuki.sevendays_states.log.parser.PlayerListPositionLogParser;
import com.yuki.sevendays_states.log.parser.ServerMetricLogParser;
import com.yuki.sevendays_states.log.parser.SleeperLogParser;
import com.yuki.sevendays_states.log.parser.VehicleLogParser;
import com.yuki.sevendays_states.repository.M_PlayerRepository;
import com.yuki.sevendays_states.repository.T_EntityKillTransactionRepository;
import com.yuki.sevendays_states.repository.T_LevelXpSummaryTransactionRepository;
import com.yuki.sevendays_states.repository.T_PlayerCurrentStateRepository;
import com.yuki.sevendays_states.repository.T_PlayerJoinTransactionRepository;
import com.yuki.sevendays_states.repository.T_PlayerLeaveTransactionRepository;
import com.yuki.sevendays_states.repository.T_PlayerPositionTransactionRepository;
import com.yuki.sevendays_states.repository.T_ServerMetricRepository;
import com.yuki.sevendays_states.repository.T_SleeperTransactionRepository;
import com.yuki.sevendays_states.repository.T_VehicleCurrentStateRepository;
import com.yuki.sevendays_states.repository.T_VehiclePositionTransactionRepository;
import com.yuki.sevendays_states.repository.T_WorldEventTransactionRepository;
import com.yuki.sevendays_states.util.Hashing;
import com.yuki.sevendays_states.util.PlayerIdentity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameLogImportService {

  private static final int MAX_PLAYER_POSITION_INFERENCE_DISTANCE = 250;
  private static final int MAX_VEHICLE_OWNER_DISTANCE = 8;
  private static final int MAX_VEHICLE_OWNER_VERTICAL_DISTANCE = 5;
  private static final int MIN_VEHICLE_OWNER_DISTANCE_ADVANTAGE = 3;
  private static final Duration MAX_VEHICLE_OWNER_POSITION_AGE = Duration.ofSeconds(30);
  private static final Duration MAX_PLAYER_MOVEMENT_GAP = Duration.ofMinutes(2);
  private static final Duration MAX_VEHICLE_MOVEMENT_GAP = Duration.ofMinutes(5);
  private static final double MAX_PLAUSIBLE_VEHICLE_SPEED_METERS_PER_SECOND = 50.0;
  private static final double MAX_ON_FOOT_SPEED_METERS_PER_SECOND = 12.0;

  private final SevenDaysDataProperties properties;
  private final M_PlayerRepository playerRepository;
  private final PlayerLookupService playerLookupService;
  private final T_PlayerCurrentStateRepository playerCurrentStateRepository;
  private final T_PlayerJoinTransactionRepository playerJoinRepository;
  private final T_PlayerLeaveTransactionRepository playerLeaveRepository;
  private final T_PlayerPositionTransactionRepository playerPositionRepository;
  private final T_EntityKillTransactionRepository entityKillRepository;
  private final T_LevelXpSummaryTransactionRepository levelXpSummaryRepository;
  private final T_SleeperTransactionRepository sleeperRepository;
  private final T_ServerMetricRepository serverMetricRepository;
  private final T_WorldEventTransactionRepository worldEventRepository;
  private final PlayerStatusService playerStatusService;
  private final T_VehicleCurrentStateRepository vehicleCurrentStateRepository;
  private final T_VehiclePositionTransactionRepository vehiclePositionRepository;
  private final TimelinePostService timelinePostService;
  private final AtomicBoolean running = new AtomicBoolean(false);

  private final GameLogLineParser lineParser = new GameLogLineParser();
  private final PlayerJoinLogParser playerJoinParser = new PlayerJoinLogParser(lineParser);
  private final PlayerLeaveLogParser playerLeaveParser = new PlayerLeaveLogParser(lineParser);
  private final PlayerDeathLogParser playerDeathParser = new PlayerDeathLogParser(lineParser);
  private final PlayerChatCommandParser playerChatCommandParser = new PlayerChatCommandParser();
  private final EntityKillLogParser entityKillParser = new EntityKillLogParser(lineParser);
  private final LevelXpSummaryLogParser levelXpSummaryParser = new LevelXpSummaryLogParser(lineParser);
  private final PlayerListPositionLogParser playerListPositionParser = new PlayerListPositionLogParser(lineParser);
  private final SleeperLogParser sleeperParser = new SleeperLogParser(lineParser);
  private final ServerMetricLogParser serverMetricParser = new ServerMetricLogParser(lineParser);
  private final AirDropLogParser airDropParser = new AirDropLogParser(lineParser);
  private final AiDirectorLogParser aiDirectorParser = new AiDirectorLogParser(lineParser);
  private final BloodMoonLogParser bloodMoonParser = new BloodMoonLogParser(lineParser);
  private final VehicleLogParser vehicleParser = new VehicleLogParser(lineParser);

  @Transactional
  public GameLogImportResult importLogs() {
    if (!running.compareAndSet(false, true)) {
      log.info("7DTD log import skipped because another import is running.");
      return emptyResult();
    }
    try {
      Path logPath = properties.logPath();
      if (Files.isRegularFile(logPath)) {
        return importLogFile(logPath);
      }
      if (!Files.isDirectory(logPath)) {
        return emptyResult();
      }
      Counter total = new Counter();
      try (Stream<Path> files = Files.list(logPath)) {
        files.filter(Files::isRegularFile)
            .sorted()
            .forEach(path -> total.add(importLogFile(path)));
      }
      GameLogImportResult result = total.toResult();
      log.info("7DTD log import completed. {}", result);
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("7DTD logs cannot be imported: " + properties.logPath(), e);
    } finally {
      running.set(false);
    }
  }

  public GameLogImportResult importLogFile(Path sourceFile) {
    Counter counter = new Counter();
    counter.filesRead++;
    List<String> lines;
    try {
      lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("log file cannot be read: " + sourceFile, e);
    }
    String sourceFileName = sourceFileName(sourceFile);
    LogImportContext context = new LogImportContext();
    importLines(sourceFileName, lines, context, counter);
    return counter.toResult();
  }

  @Transactional
  public GameLogImportResult importLogLines(String sourceFileName, List<String> lines) {
    Counter counter = new Counter();
    LogImportContext context = new LogImportContext();
    importLines(sourceFileName, lines, context, counter);
    return counter.toResult();
  }

  public StreamSession openStreamSession(String sourceFileName) {
    return new StreamSession(sourceFileName);
  }

  private void importLines(
      String sourceFileName,
      List<String> lines,
      LogImportContext context,
      Counter counter) {
    for (int i = 0; i < lines.size(); i++) {
      String rawLine = lines.get(i);
      counter.linesRead++;
      try {
        Optional<LevelXpSummaryLogEvent> xp = levelXpSummaryParser.parse(lines, i);
        if (xp.isPresent()) {
          saveLevelXpSummary(sourceFileName, xp.get(), context, counter);
          i += xp.get().consumedLineCount() - 1;
          counter.linesRead += xp.get().consumedLineCount() - 1L;
          continue;
        }
        Optional<PlayerListPositionLogEvent> playerPositions = playerListPositionParser.parse(lines, i);
        if (playerPositions.isPresent()) {
          savePlayerListPositions(sourceFileName, playerPositions.get(), context, counter);
          i += playerPositions.get().consumedLineCount() - 1;
          counter.linesRead += playerPositions.get().consumedLineCount() - 1L;
          continue;
        }
        Optional<ParsedLogLine> parsedLine = lineParser.parse(rawLine);
        if (parsedLine.isEmpty()) {
          counter.malformedLines++;
          continue;
        }
        parseSingleLine(sourceFileName, parsedLine.get(), context, counter);
      } catch (RuntimeException e) {
        counter.malformedLines++;
        log.debug("Malformed or unsupported 7DTD log line skipped.", e);
      }
    }
  }

  private void parseSingleLine(String sourceFile, ParsedLogLine line, LogImportContext context, Counter counter) {
    playerChatCommandParser.parse(line).ifPresent(command ->
        playerStatusService.updateFromChat(command.playerName(), command.command()));
    Optional<PlayerJoinLogEvent> join = playerJoinParser.parse(line);
    if (join.isPresent()) {
      Long playerId = savePlayerJoin(sourceFile, join.get(), counter);
      context.playerJoined(join.get(), playerId);
      return;
    }
    Optional<PlayerLeaveLogEvent> leave = playerLeaveParser.parse(line);
    if (leave.isPresent()) {
      savePlayerLeave(sourceFile, leave.get(), counter);
      context.playerLeft(leave.get());
      return;
    }
    Optional<EntityKillLogEvent> kill = entityKillParser.parse(line);
    if (kill.isPresent()) {
      saveEntityKill(sourceFile, kill.get(), counter);
      return;
    }
    Optional<WorldEventLogEvent> playerDeath = playerDeathParser.parse(line);
    if (playerDeath.isPresent()) {
      saveWorldEvent(sourceFile, playerDeath.get(), counter);
      return;
    }
    Optional<SleeperLogEvent> sleeper = sleeperParser.parse(line);
    if (sleeper.isPresent()) {
      saveSleeper(sourceFile, sleeper.get(), context, counter);
      return;
    }
    Optional<WorldEventLogEvent> airDrop = airDropParser.parse(line);
    if (airDrop.isPresent()) {
      saveWorldEvent(sourceFile, airDrop.get(), counter);
      return;
    }
    Optional<WorldEventLogEvent> aiDirector = aiDirectorParser.parse(line);
    if (aiDirector.isPresent()) {
      saveWorldEvent(sourceFile, aiDirector.get(), counter);
      return;
    }
    Optional<WorldEventLogEvent> bloodMoon = bloodMoonParser.parse(line);
    if (bloodMoon.isPresent()) {
      saveWorldEvent(sourceFile, bloodMoon.get(), counter);
      return;
    }
    Optional<VehicleLogEvent> vehicle = vehicleParser.parse(line);
    if (vehicle.isPresent()) {
      saveVehicle(sourceFile, vehicle.get(), counter);
      return;
    }
    serverMetricParser.parse(line).ifPresent(metric -> saveServerMetric(sourceFile, metric, counter));
  }

  private Long savePlayerJoin(String sourceFile, PlayerJoinLogEvent event, Counter counter) {
    String hash = lineHash(sourceFile, event.occurredAt().toString(), event.rawLine());
    M_Player player = upsertPlayerMaster(
        event.playerName(), event.platformId(), event.crossPlatformId(), event.occurredAt());
    Long playerId = player == null ? null : player.getId();
    savePlayerPosition(
        sourceFile,
        hash,
        event.occurredAt(),
        event.playerName(),
        event.playerEntityId(),
        playerId,
        event.positionX(),
        event.positionY(),
        event.positionZ(),
        "PLAYER_JOIN",
        "direct_log_position");
    if (!isActualPlayerJoin(event.reason())) {
      return playerId;
    }
    if (playerJoinRepository.existsBySourceLogHash(hash)) {
      return playerId;
    }
    T_PlayerJoinTransaction row = new T_PlayerJoinTransaction();
    row.setOccurredAt(event.occurredAt());
    row.setPlayerName(event.playerName());
    row.setPlayerEntityId(event.playerEntityId());
    row.setPlayerId(playerId);
    row.setPlatformId(event.platformId());
    row.setCrossPlatformId(event.crossPlatformId());
    row.setPositionX(event.positionX());
    row.setPositionY(event.positionY());
    row.setPositionZ(event.positionZ());
    row.setJoinReason(event.reason());
    row.setClientNumber(event.clientNumber());
    row.setSourceFile(sourceFile);
    row.setSourceLogHash(hash);
    playerJoinRepository.save(row);
    timelinePostService.publishGameEvent(TimelinePostType.LOGIN, playerId, event.playerName(),
        event.occurredAt(), "", coordinate(event.positionX(), event.positionY(), event.positionZ()),
        "PLAYER_JOIN", row.getId(), "PLAYER_JOIN:" + hash);
    counter.playerJoins++;
    return playerId;
  }

  private boolean isActualPlayerJoin(String reason) {
    return "JoinMultiplayer".equals(reason) || "EnterMultiplayer".equals(reason);
  }

  private void savePlayerLeave(String sourceFile, PlayerLeaveLogEvent event, Counter counter) {
    String hash = lineHash(sourceFile, event.occurredAt().toString(), event.rawLine());
    M_Player player = upsertPlayerMaster(
        event.playerName(), event.platformId(), event.crossPlatformId(), event.occurredAt());
    if (playerLeaveRepository.existsBySourceLogHash(hash)) {
      return;
    }
    T_PlayerLeaveTransaction row = new T_PlayerLeaveTransaction();
    row.setOccurredAt(event.occurredAt());
    row.setPlayerName(event.playerName());
    row.setPlayerEntityId(event.playerEntityId());
    row.setPlayerId(player == null ? null : player.getId());
    row.setPlatformId(event.platformId());
    row.setCrossPlatformId(event.crossPlatformId());
    row.setClientNumber(event.clientNumber());
    row.setSourceFile(sourceFile);
    row.setSourceLogHash(hash);
    playerLeaveRepository.save(row);
    timelinePostService.publishGameEvent(TimelinePostType.LOGOUT, player == null ? null : player.getId(),
        event.playerName(), event.occurredAt(), "", "", "PLAYER_LEAVE", row.getId(), "PLAYER_LEAVE:" + hash);
    counter.playerLeaves++;
  }

  private void saveEntityKill(String sourceFile, EntityKillLogEvent event, Counter counter) {
    if (isHostileEntityName(event.playerName())) {
      return;
    }
    String hash = lineHash(sourceFile, event.occurredAt().toString(), event.rawLine());
    if (entityKillRepository.existsBySourceLogHash(hash)) {
      return;
    }
    T_EntityKillTransaction row = new T_EntityKillTransaction();
    row.setOccurredAt(event.occurredAt());
    row.setPlayerName(event.playerName());
    row.setPlayerEntityId(event.playerEntityId());
    row.setTargetEntityType(event.targetEntityType());
    row.setTargetEntityId(event.targetEntityId());
    playerCurrentStateRepository.findById(event.playerEntityId()).filter(currentState ->
        isFreshCurrentState(currentState, event.occurredAt())).ifPresent(currentState -> {
      row.setPlayerId(currentState.getPlayerId());
      row.setPlayerPositionX(currentState.getPositionX());
      row.setPlayerPositionY(currentState.getPositionY());
      row.setPlayerPositionZ(currentState.getPositionZ());
      row.setPlayerCurrentStateUpdatedAt(currentState.getLastUpdated());
    });
    row.setSourceFile(sourceFile);
    row.setSourceLogHash(hash);
    entityKillRepository.save(row);
    timelinePostService.publishGameEvent(TimelinePostType.KILL, row.getPlayerId(), event.playerName(),
        event.occurredAt(), event.targetEntityType(),
        coordinate(row.getPlayerPositionX(), row.getPlayerPositionY(), row.getPlayerPositionZ()),
        "ENTITY_KILL", row.getId(), "ENTITY_KILL:" + hash);
    counter.entityKills++;
  }

  private boolean isHostileEntityName(String name) {
    if (name == null) {
      return true;
    }
    String normalized = name.toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith("zombie") || normalized.startsWith("animal");
  }

  private String coordinate(Integer x, Integer y, Integer z) {
    return x == null || y == null || z == null ? "" : x + ", " + y + ", " + z;
  }

  private String worldEventText(String eventType, String detail, String nearby) {
    String event = switch (eventType == null ? "" : eventType) {
      case "AIR_DROP" -> "補給物資が投下された。";
      case "WANDERING_HORDE" -> nearby + "で徘徊ホードが発生した！";
      case "SCOUT_HORDE" -> nearby + "でスクリーマーの群れを観測した！";
      case "SCREAMER_SPAWN" -> nearby + "でスクリーマーが出現した！";
      case "BLOOD_MOON" -> "ブラッドムーン予定が更新された。";
      default -> "世界でイベントが発生した。";
    };
    boolean horde = isHordeEvent(eventType);
    return horde || detail == null || detail.isBlank() ? event : event + " " + detail;
  }

  private void saveLevelXpSummary(
      String sourceFile,
      LevelXpSummaryLogEvent event,
      LogImportContext context,
      Counter counter) {
    String hash = lineHash(sourceFile, event.occurredAt().toString(), String.join("\n", event.rawLines()));
    if (levelXpSummaryRepository.existsBySourceLogHash(hash)) {
      return;
    }
    T_LevelXpSummaryTransaction row = new T_LevelXpSummaryTransaction();
    row.setOccurredAt(event.occurredAt());
    row.setXpFromLoot(event.xpFromLoot());
    row.setXpFromHarvesting(event.xpFromHarvesting());
    row.setXpFromKill(event.xpFromKill());
    row.setXpTotal(event.xpTotal());
    row.setSourceFile(sourceFile);
    row.setSourceLogHash(hash);
    levelXpSummaryRepository.save(row);
    counter.levelXpSummaries++;
  }

  private void saveSleeper(
      String sourceFile,
      SleeperLogEvent event,
      LogImportContext context,
      Counter counter) {
    String hash = lineHash(sourceFile, event.occurredAt().toString(), event.rawLine());
    Optional<ActivePlayer> inferredPlayer = inferNearestCurrentStatePlayer(
        event.positionX(), event.positionZ(), event.occurredAt())
        .or(() -> context.inferNearestActivePlayer(event.positionX(), event.positionZ()));
    Optional<T_SleeperTransaction> existing = sleeperRepository.findBySourceLogHash(hash);
    if (existing.isPresent()) {
      updateSleeperInference(existing.get(), inferredPlayer);
      inferredPlayer.ifPresent(player -> {
        if (player.trustedForPositionUpdate()) {
          savePlayerPosition(
              sourceFile,
              hash,
              event.occurredAt(),
              player.playerName(),
              player.playerEntityId(),
              player.playerId(),
              event.positionX(),
              event.positionY(),
              event.positionZ(),
              "SLEEPER_INFERRED",
              player.inferenceMethod());
          context.updatePlayerPosition(player, event.positionX(), event.positionY(), event.positionZ());
        }
      });
      return;
    }
    T_SleeperTransaction row = new T_SleeperTransaction();
    row.setOccurredAt(event.occurredAt());
    row.setTransactionType(event.transactionType());
    row.setSleeperVolumeX(event.sleeperVolumeX());
    row.setSleeperVolumeY(event.sleeperVolumeY());
    row.setSleeperVolumeZ(event.sleeperVolumeZ());
    row.setPositionX(event.positionX());
    row.setPositionY(event.positionY());
    row.setPositionZ(event.positionZ());
    row.setChunkX(event.chunkX());
    row.setChunkZ(event.chunkZ());
    row.setSleeperGroup(event.sleeperGroup());
    row.setEntityClass(event.entityClass());
    row.setEntityCount(event.entityCount());
    inferredPlayer.ifPresent(player -> {
      row.setPlayerName(player.playerName());
      row.setPlayerEntityId(player.playerEntityId());
      row.setPlayerId(player.playerId());
      row.setPlayerInferenceMethod(player.inferenceMethod());
      row.setPlayerPositionX(player.x());
      row.setPlayerPositionY(player.y());
      row.setPlayerPositionZ(player.z());
      row.setPlayerCurrentStateUpdatedAt(player.positionUpdatedAt());
    });
    row.setSourceFile(sourceFile);
    row.setSourceLogHash(hash);
    sleeperRepository.save(row);
    if (!"SLEEPER_RESTORE".equals(event.transactionType())) {
      timelinePostService.publishGameEvent(TimelinePostType.SLEEPER, row.getPlayerId(), row.getPlayerName(),
          event.occurredAt(), event.entityClass(), coordinate(event.positionX(), event.positionY(), event.positionZ()),
          "SLEEPER", row.getId(), "SLEEPER:" + hash);
    }
    inferredPlayer.ifPresent(player -> {
      if (player.trustedForPositionUpdate()) {
        savePlayerPosition(
            sourceFile,
            hash,
            event.occurredAt(),
            player.playerName(),
            player.playerEntityId(),
            player.playerId(),
            event.positionX(),
            event.positionY(),
            event.positionZ(),
            "SLEEPER_INFERRED",
            player.inferenceMethod());
        context.updatePlayerPosition(player, event.positionX(), event.positionY(), event.positionZ());
      }
    });
    if ("SLEEPER_RESTORE".equals(event.transactionType())) {
      counter.sleeperRestores++;
    } else {
      counter.sleeperSpawns++;
    }
  }

  private void updateSleeperInference(
      T_SleeperTransaction row,
      Optional<ActivePlayer> inferredPlayer) {
    if (row.getPlayerName() != null || inferredPlayer.isEmpty()) {
      return;
    }
    ActivePlayer player = inferredPlayer.get();
    row.setPlayerName(player.playerName());
    row.setPlayerEntityId(player.playerEntityId());
    row.setPlayerId(player.playerId());
    row.setPlayerInferenceMethod(player.inferenceMethod());
    row.setPlayerPositionX(player.x());
    row.setPlayerPositionY(player.y());
    row.setPlayerPositionZ(player.z());
    row.setPlayerCurrentStateUpdatedAt(player.positionUpdatedAt());
    sleeperRepository.save(row);
  }

  private Optional<ActivePlayer> inferNearestCurrentStatePlayer(int x, int z, OffsetDateTime occurredAt) {
    List<ActivePlayerDistance> distances = playerCurrentStateRepository.findByOnlineTrue().stream()
        .filter(player -> player.getPositionX() != null && player.getPositionZ() != null)
        .filter(player -> isFreshCurrentState(player, occurredAt))
        .map(player -> new ActivePlayerDistance(new ActivePlayer(
            player.getPlayerName(),
            player.getPlayerEntityId(),
            player.getPlayerId(),
            player.getLastUpdated(),
            player.getPositionX(),
            player.getPositionY(),
            player.getPositionZ(),
            "nearest_current_state_position",
            false,
            player.getLastUpdated()), distance(player.getPositionX(), player.getPositionZ(), x, z)))
        .sorted(Comparator.comparingDouble(ActivePlayerDistance::distance)
            .thenComparing(candidate -> candidate.player().playerEntityId()))
        .toList();
    if (distances.isEmpty()) {
      return Optional.empty();
    }
    ActivePlayerDistance nearest = distances.getFirst();
    if (nearest.distance() > MAX_PLAYER_POSITION_INFERENCE_DISTANCE) {
      return Optional.empty();
    }
    return Optional.of(nearest.player());
  }

  private boolean isFreshCurrentState(T_PlayerCurrentState player, OffsetDateTime referenceTime) {
    if (player.getLastUpdated() == null) {
      return false;
    }
    Duration maxAge = properties.transaction().currentStateMaxAge();
    return !player.getLastUpdated().isBefore(referenceTime.minus(maxAge));
  }

  private void savePlayerListPositions(
      String sourceFile,
      PlayerListPositionLogEvent event,
      LogImportContext context,
      Counter counter) {
    for (PlayerListPositionLogEvent.PlayerPosition player : event.players()) {
      M_Player playerMaster = upsertPlayerMaster(
          player.playerName(), player.platformId(), player.crossPlatformId(), event.occurredAt());
      Long playerId = playerMaster == null ? null : playerMaster.getId();
      String hash = lineHash(sourceFile, event.occurredAt() + "|LP|" + player.playerEntityId(), player.rawLine());
      savePlayerPosition(
          sourceFile,
          hash,
          event.occurredAt(),
          player.playerName(),
          player.playerEntityId(),
          playerId,
          player.positionX(),
          player.positionY(),
          player.positionZ(),
          "LP_COMMAND",
          "direct_telnet_lp");
      context.playerPositionObserved(player, playerId, event.occurredAt());
      upsertPlayerCurrentState(event.occurredAt(), player, playerId);
    }
    counter.playerListPositions += event.players().size();
    markMissingCurrentStatePlayersOffline(event.occurredAt(), event.players());
  }

  private void upsertPlayerCurrentState(
      OffsetDateTime occurredAt,
      PlayerListPositionLogEvent.PlayerPosition player,
      Long playerId) {
    T_PlayerCurrentState row = findCurrentStateByPlayerOrEntityOrExternalId(player, playerId)
        .orElseGet(T_PlayerCurrentState::new);
    if (row.getPlayerEntityId() != null && !row.getPlayerEntityId().equals(player.playerEntityId())) {
      playerCurrentStateRepository.delete(row);
      playerCurrentStateRepository.flush();
      row = new T_PlayerCurrentState();
    }
    row.setPlayerEntityId(player.playerEntityId());
    row.setPlayerId(playerId);
    row.setPlayerName(player.playerName());
    row.setPositionX(player.positionX());
    row.setPositionY(player.positionY());
    row.setPositionZ(player.positionZ());
    row.setRotationX(player.rotationX());
    row.setRotationY(player.rotationY());
    row.setRotationZ(player.rotationZ());
    row.setHealth(player.health());
    row.setDeaths(player.deaths());
    row.setZombies(player.zombies());
    row.setPlayers(player.players());
    row.setScore(player.score());
    row.setLevel(player.level());
    row.setPlatformId(player.platformId());
    row.setCrossPlatformId(player.crossPlatformId());
    row.setPing(player.ping());
    row.setOnline(true);
    row.setLastUpdated(occurredAt);
    playerCurrentStateRepository.save(row);
  }

  private Optional<T_PlayerCurrentState> findCurrentStateByPlayerOrEntityOrExternalId(
      PlayerListPositionLogEvent.PlayerPosition player,
      Long playerId) {
    if (playerId != null) {
      Optional<T_PlayerCurrentState> byPlayerId = newestCurrentState(
          playerCurrentStateRepository.findByPlayerId(playerId));
      if (byPlayerId.isPresent()) {
        return byPlayerId;
      }
    }
    Optional<T_PlayerCurrentState> byEntity = playerCurrentStateRepository.findById(player.playerEntityId());
    if (byEntity.isPresent()) {
      return byEntity;
    }
    Set<String> crossPlatformIds = externalIdVariants(player.crossPlatformId(), "EOS");
    if (!crossPlatformIds.isEmpty()) {
      Optional<T_PlayerCurrentState> byCrossPlatformId = newestCurrentState(
          playerCurrentStateRepository.findByCrossPlatformIdIn(crossPlatformIds));
      if (byCrossPlatformId.isPresent()) {
        return byCrossPlatformId;
      }
    }
    Set<String> platformIds = externalIdVariants(player.platformId(), "Steam");
    if (!platformIds.isEmpty()) {
      return newestCurrentState(playerCurrentStateRepository.findByPlatformIdIn(platformIds));
    }
    return Optional.empty();
  }

  private Optional<T_PlayerCurrentState> newestCurrentState(List<T_PlayerCurrentState> states) {
    return states.stream()
        .max(Comparator.comparing(T_PlayerCurrentState::getLastUpdated, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(T_PlayerCurrentState::getPlayerEntityId, Comparator.nullsFirst(Comparator.naturalOrder())));
  }

  private M_Player upsertPlayerMaster(
      String playerName,
      String platformId,
      String crossPlatformId,
      OffsetDateTime observedAt) {
    String eosUserId = stripExternalId(crossPlatformId, "EOS");
    String steamUserId = stripExternalId(platformId, "Steam");
    if (eosUserId == null && steamUserId == null) {
      return null;
    }
    String platform = eosUserId == null ? "Steam" : "EOS";
    String userId = eosUserId == null ? steamUserId : eosUserId;
    String nativePlatform = steamUserId == null || "Steam".equals(platform) ? null : "Steam";
    String nativeUserId = steamUserId == null || "Steam".equals(platform) ? null : steamUserId;
    String playerKey = PlayerIdentity.canonicalPlayerKey(platform, userId, nativePlatform, nativeUserId);
    if (playerKey == null) {
      return null;
    }
    M_Player player = playerLookupService.findExisting(
        playerKey, platform, userId, nativePlatform, nativeUserId)
        .orElseGet(M_Player::new);
    LocalDateTime seenAt = observedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    boolean created = player.getId() == null;
    player.setSourcePath("telnet:lp");
    player.setPlayerKey(playerKey);
    player.setPlatform(platform);
    player.setUserId(userId);
    player.setNativePlatform(nativePlatform);
    player.setNativeUserId(nativeUserId);
    player.setPlayerName(playerName);
    if (created || player.getFirstSeenAt() == null) {
      player.setFirstSeenAt(seenAt);
    }
    player.setLastSeenAt(seenAt);
    return playerRepository.save(player);
  }

  private String stripExternalId(String rawValue, String prefix) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }
    String trimmed = rawValue.trim();
    if ("EOS".equalsIgnoreCase(trimmed) || "Steam".equalsIgnoreCase(trimmed)) {
      return null;
    }
    return trimmed.startsWith(prefix + "_") ? trimmed.substring(prefix.length() + 1) : trimmed;
  }

  private Set<String> externalIdVariants(String rawValue, String prefix) {
    if (rawValue == null || rawValue.isBlank()) {
      return Set.of();
    }
    String trimmed = rawValue.trim();
    String bare = trimmed.startsWith(prefix + "_") ? trimmed.substring(prefix.length() + 1) : trimmed;
    Set<String> values = new LinkedHashSet<>();
    values.add(trimmed);
    values.add(prefix + "_" + bare);
    values.add(bare);
    return values;
  }

  private void markMissingCurrentStatePlayersOffline(
      OffsetDateTime occurredAt,
      List<PlayerListPositionLogEvent.PlayerPosition> observedPlayers) {
    Set<Integer> onlinePlayerIds = new HashSet<>();
    for (PlayerListPositionLogEvent.PlayerPosition player : observedPlayers) {
      onlinePlayerIds.add(player.playerEntityId());
    }
    List<T_PlayerCurrentState> missingPlayers = onlinePlayerIds.isEmpty()
        ? playerCurrentStateRepository.findByOnlineTrue()
        : playerCurrentStateRepository.findByOnlineTrueAndPlayerEntityIdNotIn(onlinePlayerIds);
    for (T_PlayerCurrentState player : missingPlayers) {
      player.setOnline(false);
      player.setLastUpdated(occurredAt);
    }
    playerCurrentStateRepository.saveAll(missingPlayers);
  }

  private void savePlayerPosition(
      String sourceFile,
      String sourceLogHash,
      OffsetDateTime occurredAt,
      String playerName,
      int playerEntityId,
      Long playerId,
      int positionX,
      Integer positionY,
      int positionZ,
      String positionSourceType,
      String inferenceMethod) {
    String sourceEventHash = lineHash(sourceFile, occurredAt + "|" + positionSourceType, sourceLogHash);
    if (playerPositionRepository.existsBySourceEventHash(sourceEventHash)) {
      return;
    }
    T_PlayerPositionTransaction row = new T_PlayerPositionTransaction();
    row.setOccurredAt(occurredAt);
    row.setPlayerName(playerName);
    row.setPlayerEntityId(playerEntityId);
    row.setPlayerId(playerId);
    row.setPositionX(positionX);
    row.setPositionY(positionY);
    row.setPositionZ(positionZ);
    row.setPositionSourceType(positionSourceType);
    row.setInferenceMethod(inferenceMethod);
    PlayerMovement movement = playerMovement(
        playerId, playerEntityId, occurredAt, positionX, positionZ, positionSourceType);
    row.setMovementDistance(movement.distance());
    row.setMovementMode(movement.mode());
    row.setVehicleEntityId(movement.vehicleEntityId());
    row.setMovementInferenceMethod(movement.inferenceMethod());
    row.setSourceEventHash(sourceEventHash);
    row.setSourceFile(sourceFile);
    playerPositionRepository.save(row);
  }

  private void saveServerMetric(String sourceFile, ServerMetricLogEvent event, Counter counter) {
    String hash = lineHash(sourceFile, event.occurredAt().toString(), event.rawLine());
    if (serverMetricRepository.existsBySourceLogHash(hash)) {
      return;
    }
    if (!shouldStoreServerMetric(event)) {
      counter.skippedServerMetrics++;
      return;
    }
    T_ServerMetric row = new T_ServerMetric();
    row.setOccurredAt(event.occurredAt());
    row.setUptimeMinutes(event.uptimeMinutes());
    row.setFps(event.fps());
    row.setHeapMb(event.heapMb());
    row.setMaxHeapMb(event.maxHeapMb());
    row.setChunks(event.chunks());
    row.setCgo(event.cgo());
    row.setPlayerCount(event.playerCount());
    row.setZombieCount(event.zombieCount());
    row.setEntityCount(event.entityCount());
    row.setEntityCountDetail(event.entityCountDetail());
    row.setItemCount(event.itemCount());
    row.setCo(event.co());
    row.setRssMb(event.rssMb());
    row.setSourceFile(sourceFile);
    row.setSourceLogHash(hash);
    serverMetricRepository.save(row);
    counter.serverMetrics++;
  }

  private void saveWorldEvent(String sourceFile, WorldEventLogEvent event, Counter counter) {
    String hash = lineHash(sourceFile, event.occurredAt().toString(), event.rawLine());
    if (worldEventRepository.existsBySourceLogHash(hash)) {
      return;
    }
    T_WorldEventTransaction row = new T_WorldEventTransaction();
    row.setOccurredAt(event.occurredAt());
    row.setEventType(event.eventType());
    row.setActorPlayerName(event.actorPlayerName());
    row.setActorPlayerEntityId(event.actorPlayerEntityId());
    if (event.actorPlayerEntityId() != null) {
      playerCurrentStateRepository.findById(event.actorPlayerEntityId())
          .ifPresent(currentState -> {
            row.setPlayerId(currentState.getPlayerId());
            if (row.getActorPlayerName() == null) {
              row.setActorPlayerName(currentState.getPlayerName());
            }
          });
    } else if (isHordeEvent(event.eventType()) && nearbyX(event) != null && nearbyZ(event) != null) {
      inferNearestCurrentStatePlayer(nearbyX(event), nearbyZ(event), event.occurredAt())
          .ifPresent(player -> {
            row.setActorPlayerName(player.playerName());
            row.setActorPlayerEntityId(player.playerEntityId());
            row.setPlayerId(player.playerId());
          });
    } else if (event.actorPlayerName() != null) {
      playerRepository.findFirstByPlayerNameOrderByLastSeenAtDesc(event.actorPlayerName())
          .ifPresent(player -> row.setPlayerId(player.getId()));
    }
    row.setDetailText(event.detailText());
    row.setPositionX(event.positionX());
    row.setPositionY(event.positionY());
    row.setPositionZ(event.positionZ());
    row.setTargetPositionX(event.targetPositionX());
    row.setTargetPositionY(event.targetPositionY());
    row.setTargetPositionZ(event.targetPositionZ());
    row.setSourceFile(sourceFile);
    row.setSourceLogHash(hash);
    row.setRawLine(event.rawLine());
    worldEventRepository.save(row);
    TimelinePostType postType = switch (event.eventType()) {
      case "PLAYER_DEATH" -> TimelinePostType.PLAYER_DEATH;
      case "BLOOD_MOON" -> TimelinePostType.BLOOD_MOON;
      case "AIR_DROP" -> TimelinePostType.AIR_DROP;
      case "WANDERING_HORDE", "SCOUT_HORDE", "SCREAMER_SPAWN" -> TimelinePostType.HORDE_ALERT;
      default -> TimelinePostType.WORLD_EVENT;
    };
    if (postType != TimelinePostType.BLOOD_MOON) {
      timelinePostService.publishGameEvent(postType, row.getPlayerId(), row.getActorPlayerName(), event.occurredAt(),
          worldEventText(event.eventType(), event.detailText(), nearbyDescription(row)),
          coordinate(row.getPositionX(), row.getPositionY(), row.getPositionZ()),
          "WORLD_EVENT", row.getId(), "WORLD_EVENT:" + hash);
    }
    counter.worldEvents++;
  }

  private boolean isHordeEvent(String eventType) {
    return "WANDERING_HORDE".equals(eventType) || "SCOUT_HORDE".equals(eventType)
        || "SCREAMER_SPAWN".equals(eventType);
  }

  private Integer nearbyX(WorldEventLogEvent event) {
    return event.targetPositionX() != null ? event.targetPositionX() : event.positionX();
  }

  private Integer nearbyZ(WorldEventLogEvent event) {
    return event.targetPositionZ() != null ? event.targetPositionZ() : event.positionZ();
  }

  private String nearbyDescription(T_WorldEventTransaction row) {
    if (row.getActorPlayerName() != null && !row.getActorPlayerName().isBlank()) {
      return row.getActorPlayerName() + "の近く";
    }
    Integer x = row.getTargetPositionX() != null ? row.getTargetPositionX() : row.getPositionX();
    Integer z = row.getTargetPositionZ() != null ? row.getTargetPositionZ() : row.getPositionZ();
    return x == null || z == null ? "観測地点" : "座標 " + x + ", " + z + " 付近";
  }

  private void saveVehicle(String sourceFile, VehicleLogEvent event, Counter counter) {
    String hash = lineHash(sourceFile, event.occurredAt().toString(), event.rawLine());
    if (vehiclePositionRepository.existsBySourceLogHash(hash)) {
      return;
    }
    T_VehicleCurrentState currentState = vehicleCurrentStateRepository.findById(event.vehicleEntityId())
        .orElseGet(T_VehicleCurrentState::new);
    boolean hasNewerState = currentState.getVehicleEntityId() == null
        || currentState.getLastUpdated() == null
        || event.occurredAt().isAfter(currentState.getLastUpdated());
    // The server emits PostInit and loaded records in the same second.  A loaded record is
    // authoritative for the owner, so it must be allowed to enrich the state written by PostInit.
    boolean enrichesOwnerAtSameTime = !hasNewerState
        && event.occurredAt().isEqual(currentState.getLastUpdated())
        && hasAuthoritativeVehicleOwner(event);
    boolean updatesCurrentState = hasNewerState || enrichesOwnerAtSameTime;
    boolean reusedVehicleEntity = hasNewerState && isReusedVehicleEntity(currentState, event);
    if (reusedVehicleEntity) {
      log.info("WATCHPOINT vehicle lifecycle reset: entityId={}, previousVehicle={}, vehicle={}, destroyedAt={}",
          event.vehicleEntityId(), currentState.getVehicleName(), event.vehicleName(), currentState.getDestroyedAt());
      currentState.setOwnerPlayerId(null);
      currentState.setOwnerCrossPlatformId(null);
      currentState.setOwnerInferenceMethod(null);
      currentState.setTotalDistance(BigDecimal.ZERO);
    }
    VehicleMovement movement = reusedVehicleEntity
        ? VehicleMovement.invalid()
        : vehicleMovement(currentState, event);
    BigDecimal movementDistance = movement.valid() ? movement.distance() : BigDecimal.ZERO;
    VehicleOwner owner = resolveVehicleOwner(event, currentState, movementDistance);
    VehicleOwner driver = movement.valid()
        ? resolveVehicleDriver(event)
        : new VehicleOwner(null, null, null);

    T_VehiclePositionTransaction history = new T_VehiclePositionTransaction();
    history.setOccurredAt(event.occurredAt());
    history.setEventType(event.eventType());
    history.setVehicleEntityId(event.vehicleEntityId());
    history.setVehicleType(event.vehicleType());
    history.setVehicleName(event.vehicleName());
    history.setOwnerPlayerId(owner.playerId());
    history.setOwnerCrossPlatformId(owner.crossPlatformId());
    history.setOwnerInferenceMethod(owner.inferenceMethod());
    history.setAttributedPlayerId(driver.playerId());
    history.setAttributionMethod(driver.inferenceMethod());
    history.setMovementValid(movement.valid());
    history.setPositionX(event.positionX());
    history.setPositionY(event.positionY());
    history.setPositionZ(event.positionZ());
    history.setMovementDistance(movementDistance);
    history.setRemovalReason(event.removalReason());
    history.setSourceFile(sourceFile);
    history.setSourceLogHash(hash);
    history.setRawLine(event.rawLine());
    vehiclePositionRepository.save(history);

    if (!updatesCurrentState) {
      counter.vehicleEvents++;
      return;
    }
    if (enrichesOwnerAtSameTime) {
      log.debug("WATCHPOINT vehicle owner enriched from same-timestamp load: entityId={}, ownerResolved={}",
          event.vehicleEntityId(), owner.playerId() != null);
    }

    currentState.setVehicleEntityId(event.vehicleEntityId());
    currentState.setVehicleType(event.vehicleType());
    currentState.setVehicleName(event.vehicleName());
    currentState.setOwnerPlayerId(owner.playerId());
    currentState.setOwnerCrossPlatformId(owner.crossPlatformId());
    currentState.setOwnerInferenceMethod(owner.inferenceMethod());
    if (event.positionX() != null && event.positionZ() != null) {
      currentState.setPositionX(event.positionX());
      currentState.setPositionY(event.positionY());
      currentState.setPositionZ(event.positionZ());
      BigDecimal totalDistance = currentState.getTotalDistance() == null
          ? BigDecimal.ZERO
          : currentState.getTotalDistance();
      currentState.setTotalDistance(totalDistance.add(movementDistance));
    }
    boolean permanentlyDestroyed = isVehiclePermanentlyDestroyed(event);
    currentState.setActive(!permanentlyDestroyed);
    currentState.setDestroyedAt(permanentlyDestroyed ? event.occurredAt() : null);
    currentState.setLastUpdated(event.occurredAt());
    currentState.setSourceFile(sourceFile);
    currentState.setSourceLogHash(hash);
    vehicleCurrentStateRepository.save(currentState);
    counter.vehicleEvents++;
  }

  private VehicleOwner resolveVehicleOwner(
      VehicleLogEvent event,
      T_VehicleCurrentState currentState,
      BigDecimal movementDistance) {
    String ownerCrossPlatformId = event.ownerCrossPlatformId() == null
        ? currentState.getOwnerCrossPlatformId()
        : event.ownerCrossPlatformId();
    Long ownerPlayerId = currentState.getOwnerPlayerId();
    String inferenceMethod = currentState.getOwnerInferenceMethod();
    if (hasAuthoritativeVehicleOwner(event)) {
      ownerPlayerId = findPlayerByCrossPlatformId(event.ownerCrossPlatformId())
          .map(M_Player::getId)
          .orElse(null);
      inferenceMethod = "vehicle_log_owner";
    } else if (ownerCrossPlatformId != null && ownerPlayerId == null) {
      ownerPlayerId = findPlayerByCrossPlatformId(ownerCrossPlatformId)
          .map(M_Player::getId)
          .orElse(null);
    }
    if (ownerPlayerId == null
        && event.positionX() != null
        && event.positionZ() != null
        && ("VEHICLE_POST_INIT".equals(event.eventType())
            || movementDistance.compareTo(BigDecimal.ONE) >= 0)) {
      Optional<T_PlayerCurrentState> inferred = inferVehicleOwnerByPosition(event);
      if (inferred.isPresent()) {
        T_PlayerCurrentState player = inferred.get();
        ownerPlayerId = player.getPlayerId();
        ownerCrossPlatformId = player.getCrossPlatformId();
        inferenceMethod = "nearest_fresh_player_position";
      }
    }
    return new VehicleOwner(ownerPlayerId, ownerCrossPlatformId, inferenceMethod);
  }

  private Optional<T_PlayerCurrentState> inferVehicleOwnerByPosition(VehicleLogEvent event) {
    List<VehicleOwnerCandidate> candidates = playerCurrentStateRepository.findByOnlineTrue().stream()
        .filter(player -> player.getPlayerId() != null)
        .filter(player -> player.getPositionX() != null && player.getPositionZ() != null)
        .filter(player -> event.positionY() == null || player.getPositionY() == null
            || Math.abs(player.getPositionY() - event.positionY()) <= MAX_VEHICLE_OWNER_VERTICAL_DISTANCE)
        .filter(player -> isFreshVehicleOwnerPosition(player, event.occurredAt()))
        .map(player -> new VehicleOwnerCandidate(player, distance(
            player.getPositionX(), player.getPositionZ(), event.positionX(), event.positionZ())))
        .filter(candidate -> candidate.distance() <= MAX_VEHICLE_OWNER_DISTANCE)
        .sorted(Comparator.comparingDouble(VehicleOwnerCandidate::distance))
        .toList();
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    VehicleOwnerCandidate nearest = candidates.getFirst();
    if (candidates.size() > 1
        && candidates.get(1).distance() - nearest.distance() < MIN_VEHICLE_OWNER_DISTANCE_ADVANTAGE) {
      return Optional.empty();
    }
    return Optional.of(nearest.player());
  }

  private VehicleOwner resolveVehicleDriver(VehicleLogEvent event) {
    return inferVehicleOwnerByPosition(event)
        .map(player -> new VehicleOwner(
            player.getPlayerId(), player.getCrossPlatformId(), "online_near_vehicle_position"))
        .orElseGet(() -> new VehicleOwner(null, null, null));
  }

  private boolean isFreshVehicleOwnerPosition(T_PlayerCurrentState player, OffsetDateTime eventTime) {
    if (player.getLastUpdated() == null || player.getLastUpdated().isAfter(eventTime.plusSeconds(5))) {
      return false;
    }
    return !player.getLastUpdated().isBefore(eventTime.minus(MAX_VEHICLE_OWNER_POSITION_AGE));
  }

  private boolean isReusedVehicleEntity(T_VehicleCurrentState currentState, VehicleLogEvent event) {
    if (!"VEHICLE_POST_INIT".equals(event.eventType())
        || currentState.getVehicleEntityId() == null) {
      return false;
    }
    // Entity ids are stable across unload/reload and can reappear far from their last logged
    // position.  Only a previously destroyed entity starts a new lifecycle and resets distance.
    return currentState.getDestroyedAt() != null;
  }

  private Optional<M_Player> findPlayerByCrossPlatformId(String crossPlatformId) {
    if (crossPlatformId == null || crossPlatformId.isBlank()) {
      return Optional.empty();
    }
    String normalized = crossPlatformId.trim();
    if (normalized.regionMatches(true, 0, "EOS_", 0, 4)) {
      String eosUserId = normalized.substring(4);
      return playerLookupService.findExisting("EOS:" + eosUserId, "EOS", eosUserId, null, null);
    }
    if (normalized.regionMatches(true, 0, "Steam_", 0, 6)) {
      String steamUserId = normalized.substring(6);
      return playerLookupService.findExisting(
          "Steam:" + steamUserId, "Steam", steamUserId, "Steam", steamUserId);
    }
    return Optional.empty();
  }

  private boolean hasAuthoritativeVehicleOwner(VehicleLogEvent event) {
    return event.ownerCrossPlatformId() != null && !event.ownerCrossPlatformId().isBlank();
  }

  private boolean isVehiclePermanentlyDestroyed(VehicleLogEvent event) {
    return "VEHICLE_REMOVED".equals(event.eventType())
        && (event.removalReason() == null || !"unloaded".equalsIgnoreCase(event.removalReason().trim()));
  }

  private VehicleMovement vehicleMovement(T_VehicleCurrentState currentState, VehicleLogEvent event) {
    if ("VEHICLE_POST_INIT".equals(event.eventType())
        || "VEHICLE_LOADED".equals(event.eventType())
        || currentState.getVehicleEntityId() == null
        || currentState.getPositionX() == null
        || currentState.getPositionZ() == null
        || event.positionX() == null
        || event.positionZ() == null
        || currentState.getLastUpdated() == null
        || !event.occurredAt().isAfter(currentState.getLastUpdated())) {
      return VehicleMovement.invalid();
    }
    Duration elapsed = Duration.between(currentState.getLastUpdated(), event.occurredAt());
    if (elapsed.compareTo(MAX_VEHICLE_MOVEMENT_GAP) > 0) {
      return VehicleMovement.invalid();
    }
    double horizontalDistance = distance(currentState.getPositionX(), currentState.getPositionZ(),
        event.positionX(), event.positionZ());
    if (horizontalDistance / Math.max(1, elapsed.toSeconds())
        > MAX_PLAUSIBLE_VEHICLE_SPEED_METERS_PER_SECOND) {
      return VehicleMovement.invalid();
    }
    return new VehicleMovement(
        BigDecimal.valueOf(horizontalDistance).setScale(1, RoundingMode.HALF_UP), true);
  }

  private PlayerMovement playerMovement(
      Long playerId,
      int playerEntityId,
      OffsetDateTime occurredAt,
      int positionX,
      int positionZ,
      String positionSourceType) {
    if (!"LP_COMMAND".equals(positionSourceType)) {
      return PlayerMovement.unknown();
    }
    Optional<T_PlayerPositionTransaction> previous = playerId == null
        ? playerPositionRepository.findTopByPlayerEntityIdOrderByOccurredAtDescIdDesc(playerEntityId)
        : playerPositionRepository.findTopByPlayerIdOrderByOccurredAtDescIdDesc(playerId);
    if (previous.isEmpty()
        || previous.get().getOccurredAt() == null
        || !occurredAt.isAfter(previous.get().getOccurredAt())
        || previous.get().getOccurredAt().isBefore(occurredAt.minus(MAX_PLAYER_MOVEMENT_GAP))) {
      return PlayerMovement.unknown();
    }
    double movement = distance(previous.get().getPositionX(), previous.get().getPositionZ(), positionX, positionZ);
    BigDecimal measured = BigDecimal.valueOf(movement).setScale(1, RoundingMode.HALF_UP);
    if (movement < 1) {
      return new PlayerMovement(measured, "STATIONARY", null, "consecutive_lp_position");
    }
    if (playerId != null) {
      Optional<T_VehiclePositionTransaction> vehicle = vehiclePositionRepository
          .findTopByAttributedPlayerIdAndMovementValidTrueAndOccurredAtBetweenOrderByOccurredAtDescIdDesc(
              playerId, previous.get().getOccurredAt().minusSeconds(5), occurredAt.plusSeconds(5));
      if (vehicle.isPresent()
          && vehicle.get().getPositionX() != null
          && vehicle.get().getPositionZ() != null
          && distance(vehicle.get().getPositionX(), vehicle.get().getPositionZ(), positionX, positionZ)
              <= MAX_VEHICLE_OWNER_DISTANCE) {
        return new PlayerMovement(measured, "VEHICLE", vehicle.get().getVehicleEntityId(),
            "matched_verified_vehicle_position");
      }
    }
    double seconds = Duration.between(previous.get().getOccurredAt(), occurredAt).toMillis() / 1000.0;
    if (movement / Math.max(1.0, seconds) <= MAX_ON_FOOT_SPEED_METERS_PER_SECOND) {
      return new PlayerMovement(measured, "ON_FOOT", null, "plausible_on_foot_speed");
    }
    return new PlayerMovement(measured, "UNKNOWN", null, "movement_speed_ambiguous");
  }

  private boolean shouldStoreServerMetric(ServerMetricLogEvent event) {
    return serverMetricRepository.findTopByOrderByOccurredAtDesc()
        .map(last -> {
          Duration interval = properties.log().serverMetricInterval();
          return !event.occurredAt().isBefore(last.getOccurredAt().plus(interval));
        })
        .orElse(true);
  }

  private String sourceFileName(Path sourceFile) {
    Path logRoot = properties.logPath().toAbsolutePath().normalize();
    Path absolute = sourceFile.toAbsolutePath().normalize();
    if (absolute.startsWith(logRoot)) {
      return logRoot.relativize(absolute).toString();
    }
    return absolute.toString();
  }

  private String lineHash(String sourceFile, String occurredAt, String content) {
    return Hashing.sha256(sourceFile + "|" + occurredAt + "|" + content);
  }

  private GameLogImportResult emptyResult() {
    return new Counter().toResult();
  }

  public class StreamSession {
    private final String sourceFileName;
    private final LogImportContext context = new LogImportContext();
    private final List<String> pendingLines = new ArrayList<>();

    private StreamSession(String sourceFileName) {
      this.sourceFileName = sourceFileName;
    }

    public synchronized GameLogImportResult acceptLine(String rawLine) {
      Counter counter = new Counter();
      if (pendingLines.isEmpty()) {
        pendingLines.add(rawLine);
        return counter.toResult();
      }
      if (startsNextEvent(rawLine)) {
        flushInto(counter);
      }
      pendingLines.add(rawLine);
      if (rawLine.startsWith("Total of ") && rawLine.endsWith(" in the game")) {
        flushInto(counter);
      }
      return counter.toResult();
    }

    public synchronized GameLogImportResult flush() {
      Counter counter = new Counter();
      flushInto(counter);
      return counter.toResult();
    }

    private boolean startsNextEvent(String rawLine) {
      if (lineParser.parse(rawLine).isEmpty()) {
        return false;
      }
      if (pendingLines.isEmpty()) {
        return false;
      }
      String first = pendingLines.getFirst();
      if (levelXpSummaryParser.matches(first) && rawLine.contains("CVarLogValue: $xpFrom")) {
        return false;
      }
      return true;
    }

    private void flushInto(Counter counter) {
      if (pendingLines.isEmpty()) {
        return;
      }
      importLines(sourceFileName, List.copyOf(pendingLines), context, counter);
      pendingLines.clear();
    }
  }

  private static class Counter {
    private long filesRead;
    private long linesRead;
    private long playerJoins;
    private long playerLeaves;
    private long playerListPositions;
    private long entityKills;
    private long levelXpSummaries;
    private long sleeperSpawns;
    private long sleeperRestores;
    private long serverMetrics;
    private long skippedServerMetrics;
    private long worldEvents;
    private long vehicleEvents;
    private long malformedLines;

    private void add(GameLogImportResult result) {
      filesRead += result.filesRead();
      linesRead += result.linesRead();
      playerJoins += result.playerJoins();
      playerLeaves += result.playerLeaves();
      playerListPositions += result.playerListPositions();
      entityKills += result.entityKills();
      levelXpSummaries += result.levelXpSummaries();
      sleeperSpawns += result.sleeperSpawns();
      sleeperRestores += result.sleeperRestores();
      serverMetrics += result.serverMetrics();
      skippedServerMetrics += result.skippedServerMetrics();
      worldEvents += result.worldEvents();
      vehicleEvents += result.vehicleEvents();
      malformedLines += result.malformedLines();
    }

    private GameLogImportResult toResult() {
      return new GameLogImportResult(
          filesRead,
          linesRead,
          playerJoins,
          playerLeaves,
          playerListPositions,
          entityKills,
          levelXpSummaries,
          sleeperSpawns,
          sleeperRestores,
          serverMetrics,
          skippedServerMetrics,
          worldEvents,
          vehicleEvents,
          malformedLines);
    }
  }

  private static class LogImportContext {
    private final Map<Integer, ActivePlayer> activePlayers = new HashMap<>();

    private void playerJoined(PlayerJoinLogEvent event, Long playerId) {
      activePlayers.put(event.playerEntityId(), new ActivePlayer(
          event.playerName(),
          event.playerEntityId(),
          playerId,
          event.occurredAt(),
          event.positionX(),
          event.positionY(),
          event.positionZ(),
          "join_position",
          true,
          event.occurredAt()));
    }

    private void playerLeft(PlayerLeaveLogEvent event) {
      activePlayers.remove(event.playerEntityId());
    }

    private void playerPositionObserved(
        PlayerListPositionLogEvent.PlayerPosition event,
        Long playerId,
        OffsetDateTime occurredAt) {
      activePlayers.put(event.playerEntityId(), new ActivePlayer(
          event.playerName(),
          event.playerEntityId(),
          playerId,
          occurredAt,
          event.positionX(),
          event.positionY(),
          event.positionZ(),
          "direct_telnet_lp",
          true,
          occurredAt));
    }

    private Optional<ActivePlayer> inferSingleActivePlayer() {
      if (activePlayers.size() != 1) {
        return Optional.empty();
      }
      return activePlayers.values().stream().findFirst();
    }

    private Optional<ActivePlayer> inferNearestActivePlayer(int x, int z) {
      if (activePlayers.isEmpty()) {
        return Optional.empty();
      }
      if (activePlayers.size() == 1) {
        return inferSingleActivePlayer()
            .map(player -> player.withInferenceMethod("single_active_player_session", true));
      }
      List<ActivePlayerDistance> distances = activePlayers.values().stream()
          .filter(player -> player.x() != null && player.z() != null)
          .map(player -> new ActivePlayerDistance(player, distance(player, x, z)))
          .sorted(Comparator.comparingDouble(ActivePlayerDistance::distance)
              .thenComparing(candidate -> candidate.player().playerEntityId()))
          .toList();
      if (distances.isEmpty()) {
        return Optional.empty();
      }
      ActivePlayerDistance nearest = distances.getFirst();
      if (nearest.distance() > MAX_PLAYER_POSITION_INFERENCE_DISTANCE) {
        return Optional.empty();
      }
      return Optional.of(nearest.player()
          .withInferenceMethod("nearest_active_player_latest_position", false));
    }

    private void updatePlayerPosition(ActivePlayer player, int x, Integer y, int z) {
      activePlayers.computeIfPresent(player.playerEntityId(), (key, current) -> current.withPosition(x, y, z));
    }

    private static double distance(ActivePlayer player, int x, int z) {
      return GameLogImportService.distance(player.x(), player.z(), x, z);
    }
  }

  private static double distance(int fromX, int fromZ, int toX, int toZ) {
    long dx = (long) fromX - toX;
    long dz = (long) fromZ - toZ;
    return Math.sqrt(dx * dx + dz * dz);
  }

  private record ActivePlayer(
      String playerName,
      int playerEntityId,
      Long playerId,
      OffsetDateTime joinedAt,
      Integer x,
      Integer y,
      Integer z,
      String inferenceMethod,
      boolean trustedForPositionUpdate,
      OffsetDateTime positionUpdatedAt) {

    private ActivePlayer withInferenceMethod(String method, boolean trusted) {
      return new ActivePlayer(playerName, playerEntityId, playerId, joinedAt, x, y, z, method, trusted, positionUpdatedAt);
    }

    private ActivePlayer withPosition(Integer newX, Integer newY, Integer newZ) {
      return new ActivePlayer(
          playerName,
          playerEntityId,
          playerId,
          joinedAt,
          newX,
          newY,
          newZ,
          inferenceMethod,
          trustedForPositionUpdate,
          OffsetDateTime.now());
    }
  }

  private record ActivePlayerDistance(ActivePlayer player, double distance) {
  }

  private record VehicleOwner(Long playerId, String crossPlatformId, String inferenceMethod) {
  }

  private record VehicleOwnerCandidate(T_PlayerCurrentState player, double distance) {
  }

  private record VehicleMovement(BigDecimal distance, boolean valid) {
    private static VehicleMovement invalid() {
      return new VehicleMovement(BigDecimal.ZERO, false);
    }
  }

  private record PlayerMovement(
      BigDecimal distance,
      String mode,
      Integer vehicleEntityId,
      String inferenceMethod) {
    private static PlayerMovement unknown() {
      return new PlayerMovement(BigDecimal.ZERO, "UNKNOWN", null, null);
    }
  }
}
