package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import com.yuki.sevendays_states.entity.TimelinePostType;
import com.yuki.sevendays_states.repository.T_TimelinePostRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

/** Finds old, evidence-rich exploration clusters. It never reads current player state. */
@Service
@RequiredArgsConstructor
public class SurvivorMarkCandidateService {

  private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
  private static final int CLUSTER_METERS = 100;

  private final JdbcTemplate jdbcTemplate;
  private final T_TimelinePostRepository postRepository;

  public Optional<Candidate> select(LocalDate publicationDate, SurvivorMarkProperties properties) {
    OffsetDateTime from = publicationDate.minusDays(properties.sourceMaxAgeDays())
        .atStartOfDay(JAPAN).toOffsetDateTime();
    // The upper bound is the beginning of the day before the minimum age, therefore the source
    // contains complete calendar days only (for 2..5 this is exactly the dates 2–5 days ago).
    OffsetDateTime to = publicationDate.minusDays(properties.sourceMinAgeDays() - 1)
        .atStartOfDay(JAPAN).toOffsetDateTime();
    Map<Grid, Aggregate> clusters = new HashMap<>();
    loadKills(from, to, clusters);
    loadSleepers(from, to, clusters);
    loadPositions(from, to, clusters);

    OffsetDateTime reusableAfter = publicationDate.minusDays(properties.locationCooldownDays())
        .atStartOfDay(JAPAN).toOffsetDateTime();
    List<Candidate> candidates = clusters.values().stream()
        .filter(Aggregate::isMeaningful)
        .map(Aggregate::toCandidate)
        .filter(candidate -> !postRepository.existsByPostTypeAndCoordinateAndPublishedAtAfter(
            TimelinePostType.SURVIVOR_MARK.name(), candidate.coordinate(), reusableAfter))
        .sorted(Comparator.comparingInt(Candidate::score).reversed())
        .limit(8)
        .map(this::withNearestPoi)
        .toList();
    if (candidates.isEmpty()) return Optional.empty();

    // Pick among the strongest candidates deterministically. This avoids a favourite POI while
    // keeping retries on the same day stable.
    int pool = Math.min(3, candidates.size());
    SplittableRandom random = new SplittableRandom(publicationDate.toEpochDay() ^ 0x4d41524bL);
    return Optional.of(candidates.get(random.nextInt(pool)));
  }

  private void loadKills(OffsetDateTime from, OffsetDateTime to, Map<Grid, Aggregate> clusters) {
    jdbcTemplate.query("""
        select player_position_x as x, player_position_z as z, target_entity_type as target
        from t_entity_kill_transaction
        where occurred_at >= ? and occurred_at < ?
          and player_position_x is not null and player_position_z is not null
        """, (RowCallbackHandler) resultSet -> add(clusters, resultSet.getInt("x"), resultSet.getInt("z"),
            EventKind.KILL, resultSet.getString("target"), 1), from, to);
  }

  private void loadSleepers(OffsetDateTime from, OffsetDateTime to, Map<Grid, Aggregate> clusters) {
    jdbcTemplate.query("""
        select coalesce(player_position_x, position_x) as x, coalesce(player_position_z, position_z) as z,
               entity_class as target, coalesce(entity_count, 1) as entity_count
        from t_sleeper_transaction
        where occurred_at >= ? and occurred_at < ? and transaction_type = 'SLEEPER_SPAWN'
        """, (RowCallbackHandler) resultSet -> add(clusters, resultSet.getInt("x"), resultSet.getInt("z"),
            EventKind.SLEEPER, resultSet.getString("target"), resultSet.getInt("entity_count")), from, to);
  }

  private void loadPositions(OffsetDateTime from, OffsetDateTime to, Map<Grid, Aggregate> clusters) {
    jdbcTemplate.query("""
        select position_x as x, position_z as z
        from t_player_position_transaction
        where occurred_at >= ? and occurred_at < ?
        """, (RowCallbackHandler) resultSet -> add(clusters, resultSet.getInt("x"), resultSet.getInt("z"),
            EventKind.POSITION, "", 1), from, to);
  }

  private void add(Map<Grid, Aggregate> clusters, int x, int z, EventKind kind, String target, int count) {
    Grid grid = new Grid(Math.floorDiv(x, CLUSTER_METERS), Math.floorDiv(z, CLUSTER_METERS));
    clusters.computeIfAbsent(grid, Aggregate::new).add(x, z, kind, target, Math.max(1, count));
  }

  private Candidate withNearestPoi(Candidate candidate) {
    List<String> poiNames = jdbcTemplate.query("""
        select poi_name from m_world_poi
        where abs(x - ?) <= 300 and abs(z - ?) <= 300
        order by ((x - ?) * (x - ?) + (z - ?) * (z - ?)) asc
        limit 1
        """, (rs, rowNum) -> rs.getString("poi_name"),
        candidate.x(), candidate.z(), candidate.x(), candidate.x(), candidate.z(), candidate.z());
    // Keep the canonical POI name from the imported world data. Translation is deliberately a
    // presentation concern, so the scheduled service never depends on the web package.
    String poi = poiNames.isEmpty() ? "" : poiNames.getFirst();
    return candidate.withPoi(poi);
  }

  private enum EventKind { KILL, SLEEPER, POSITION }

  private record Grid(int x, int z) { }

  private static final class Aggregate {
    private final Grid grid;
    private int xTotal;
    private int zTotal;
    private int samples;
    private int kills;
    private int sleepers;
    private int positions;
    private final List<String> zombieTypes = new ArrayList<>();

    private Aggregate(Grid grid) { this.grid = grid; }

    private void add(int x, int z, EventKind kind, String target, int count) {
      xTotal += x;
      zTotal += z;
      samples++;
      switch (kind) {
        case KILL -> {
          kills += count;
          if (target != null && !target.isBlank()) zombieTypes.add(target);
        }
        case SLEEPER -> {
          sleepers += count;
          if (target != null && !target.isBlank()) zombieTypes.add(target);
        }
        case POSITION -> positions += count;
      }
    }

    private boolean isMeaningful() {
      return kills > 0 || sleepers > 0 || positions >= 5;
    }

    private Candidate toCandidate() {
      int special = (int) zombieTypes.stream().filter(SurvivorMarkCandidateService::isSpecial).count();
      int score = kills * 15 + sleepers * 8 + Math.min(positions, 12) + special * 20;
      return new Candidate(grid.x + ":" + grid.z, xTotal / samples, zTotal / samples,
          kills, sleepers, positions, zombieTypes.stream().distinct().limit(3).toList(), score, "");
    }
  }

  private static boolean isSpecial(String entityType) {
    String value = entityType == null ? "" : entityType.toLowerCase(Locale.ROOT);
    return value.contains("radiated") || value.contains("screamer") || value.contains("cop")
        || value.contains("demolisher") || value.contains("feral") || value.contains("wight")
        || value.contains("soldier");
  }

  public record Candidate(String key, int x, int z, int kills, int sleepers, int positions,
                          List<String> zombieTypes, int score, String poi) {
    /** Stable 100m-cell centre: both UI output and duplicate suppression refer to the same place. */
    public String coordinate() {
      String[] cells = key.split(":", 2);
      return "X:%d Z:%d".formatted(
          Integer.parseInt(cells[0]) * CLUSTER_METERS + CLUSTER_METERS / 2,
          Integer.parseInt(cells[1]) * CLUSTER_METERS + CLUSTER_METERS / 2);
    }
    Candidate withPoi(String value) {
      return new Candidate(key, x, z, kills, sleepers, positions, zombieTypes, score, value);
    }
  }
}
