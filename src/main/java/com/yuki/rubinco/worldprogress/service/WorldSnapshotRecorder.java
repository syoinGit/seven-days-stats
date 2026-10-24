package com.yuki.rubinco.worldprogress.service;

import com.yuki.rubinco.timeline.entity.T_TimelinePost;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import com.yuki.rubinco.worldprogress.entity.M_ProgressDefinition;
import com.yuki.rubinco.worldprogress.entity.T_ProgressEvent;
import com.yuki.rubinco.worldprogress.entity.T_ProgressState;
import com.yuki.rubinco.worldprogress.entity.T_WorldSnapshot;
import com.yuki.rubinco.worldprogress.repository.M_GameWorldRepository;
import com.yuki.rubinco.worldprogress.repository.M_ProgressDefinitionRepository;
import com.yuki.rubinco.worldprogress.repository.T_ProgressEventRepository;
import com.yuki.rubinco.worldprogress.repository.T_ProgressStateRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldSnapshotRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores one validated parse result atomically and only then moves the world's latest pointer. */
@Service
@RequiredArgsConstructor
public class WorldSnapshotRecorder {
  private final M_GameWorldRepository worlds;
  private final T_WorldSnapshotRepository snapshots;
  private final M_ProgressDefinitionRepository definitions;
  private final T_ProgressStateRepository states;
  private final T_ProgressEventRepository events;
  private final T_TimelinePostRepository timeline;

  public record SnapshotInput(
      String sourceSha256, Integer containerVersion, String parserVersion,
      OffsetDateTime capturedAt, OffsetDateTime sourceModifiedAt,
      String generationJson, String extractedJson,
      // Keyed by progress_key. Missing or unsupported definitions are stored as UNKNOWN.
      Map<String, ProgressState> states) { }

  public record Recorded(T_WorldSnapshot snapshot, List<T_ProgressEvent> events) { }

  /** Empty when the same content was already recorded with the same parser version. */
  @Transactional
  public Optional<Recorded> record(Long worldId, SnapshotInput input) {
    M_GameWorld world = worlds.findById(worldId).orElseThrow();
    // Serialize with world switching so a snapshot never lands on a just-retired generation.
    world = worlds.findByGameIdForUpdate(world.getGameId()).stream()
        .filter(candidate -> candidate.getId().equals(worldId)).findFirst().orElseThrow();
    if (!world.isActive()) {
      throw new IllegalStateException("World generation is no longer active");
    }
    if (snapshots.findByGameWorldIdAndSourceSha256AndParserVersion(
        worldId, input.sourceSha256(), input.parserVersion()).isPresent()) {
      return Optional.empty();
    }
    OffsetDateTime now = OffsetDateTime.now();
    T_WorldSnapshot previous = world.getLatestSnapshotId() == null ? null
        : snapshots.findById(world.getLatestSnapshotId()).orElse(null);
    Map<Long, String> previousStates = previous == null ? Map.of()
        : states.findByWorldSnapshotId(previous.getId()).stream().collect(
            Collectors.toMap(T_ProgressState::getProgressDefinitionId, T_ProgressState::getState));

    T_WorldSnapshot snapshot = new T_WorldSnapshot();
    snapshot.setGameWorldId(worldId);
    snapshot.setSourceSha256(input.sourceSha256());
    snapshot.setContainerVersion(input.containerVersion());
    snapshot.setParserVersion(input.parserVersion());
    snapshot.setCapturedAt(input.capturedAt());
    snapshot.setSourceModifiedAt(input.sourceModifiedAt());
    snapshot.setParsedAt(now);
    snapshot.setValidationStatus("VALID");
    snapshot.setGenerationJson(input.generationJson());
    snapshot.setExtractedJson(input.extractedJson());
    snapshot = snapshots.save(snapshot);

    List<M_ProgressDefinition> gameDefinitions =
        definitions.findByGameIdOrderBySortOrderAscIdAsc(world.getGameId());
    List<T_ProgressEvent> recordedEvents = new ArrayList<>();
    for (M_ProgressDefinition definition : gameDefinitions) {
      ProgressState state = definition.isSupported()
          ? input.states().getOrDefault(definition.getProgressKey(), ProgressState.UNKNOWN)
          : ProgressState.UNKNOWN;
      T_ProgressState row = new T_ProgressState();
      row.setWorldSnapshotId(snapshot.getId());
      row.setProgressDefinitionId(definition.getId());
      row.setState(state.name());
      states.save(row);

      String kind = changeKind(previous, previousStates.get(definition.getId()), state,
          input.parserVersion(), definition.isSupported());
      if (kind != null) {
        T_ProgressEvent event = new T_ProgressEvent();
        event.setGameWorldId(worldId);
        event.setProgressDefinitionId(definition.getId());
        event.setWorldSnapshotId(snapshot.getId());
        event.setPreviousState(previous == null ? null : previousStates.get(definition.getId()));
        event.setNewState(state.name());
        event.setChangeKind(kind);
        event.setFirstObservedAt(now);
        recordedEvents.add(events.save(event));
      }
    }
    world.setLatestSnapshotId(snapshot.getId());
    worlds.save(world);
    publishAchievements(world, recordedEvents, gameDefinitions);
    return Optional.of(new Recorded(snapshot, List.copyOf(recordedEvents)));
  }

  static String changeKind(T_WorldSnapshot previous, String previousState, ProgressState state,
      String parserVersion, boolean supported) {
    if (!supported) return null;
    if (previous == null) return "INITIAL";
    // UNKNOWN is only ever stored for items the parser did not cover at that time.
    if (previousState == null || ProgressState.UNKNOWN.name().equals(previousState)) {
      return state == ProgressState.UNKNOWN ? null : "NEWLY_SUPPORTED";
    }
    if (previousState.equals(state.name())) return null;
    if (!parserVersion.equals(previous.getParserVersion())) return "PARSER_CHANGE";
    if (ProgressState.ACHIEVED.name().equals(previousState) && state == ProgressState.NOT_ACHIEVED) {
      return "REGRESSED";
    }
    return "CHANGED";
  }

  /** Only genuine in-game changes reach the shared timeline; first observations stay quiet. */
  private void publishAchievements(M_GameWorld world, List<T_ProgressEvent> recordedEvents,
      List<M_ProgressDefinition> gameDefinitions) {
    Map<Long, M_ProgressDefinition> byId = gameDefinitions.stream()
        .collect(Collectors.toMap(M_ProgressDefinition::getId, Function.identity()));
    for (T_ProgressEvent event : recordedEvents) {
      if (!"CHANGED".equals(event.getChangeKind())
          || !ProgressState.ACHIEVED.name().equals(event.getNewState())) {
        continue;
      }
      M_ProgressDefinition definition = byId.get(event.getProgressDefinitionId());
      String sourceHash = "WORLD_PROGRESS:" + event.getId();
      if (timeline.existsBySourceHash(sourceHash)) continue;
      T_TimelinePost post = new T_TimelinePost();
      post.setGameId(world.getGameId());
      post.setEventId(sourceHash);
      post.setPostType("PROGRESS");
      post.setTitle(definition.getDisplayName() + "を確認");
      post.setMessage(world.getDisplayName() + "で「" + definition.getDisplayName()
          + "」の達成をサイトが確認しました。表示時刻は確認した時刻で、実際の達成時刻とは異なる場合があります。");
      post.setMetadataJson("{}");
      post.setActorType("SYSTEM");
      post.setActorName("Octarine");
      post.setSourceType("WORLD_PROGRESS");
      post.setSourceId(event.getId());
      post.setSourceHash(sourceHash);
      post.setPriority(100);
      post.setVisible(world.isPublished());
      post.setPublishedAt(event.getFirstObservedAt());
      timeline.save(post);
    }
  }
}
