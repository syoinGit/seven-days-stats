package com.yuki.rubinco.watchpoint.readmodel;

import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.watchpoint.ingest.SevenDaysDataProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Queries server telemetry and derives health from the same chronological observation window. */
@Service
@RequiredArgsConstructor
public class ServerOverviewService {

  private final JdbcTemplate jdbcTemplate;
  private final SevenDaysDataProperties properties;
  private final DisplayTimeFormatter displayTimeFormatter = new DisplayTimeFormatter();

  public ServerState current() {
    return withOnlinePlayerCount(latestServerState());
  }

  private ServerState latestServerState() {
    List<ServerState> states = jdbcTemplate.query("""
        select occurred_at, fps, player_count, zombie_count, entity_count, rss_mb
        from t_server_metric
        order by occurred_at desc
        limit 1
        """, (rs, rowNum) -> new ServerState(
        displayTimeFormatter.format(rs.getObject("occurred_at")),
        rs.getBigDecimal("fps"),
        rs.getObject("player_count", Integer.class),
        rs.getObject("zombie_count", Integer.class),
        rs.getObject("entity_count", Integer.class),
        rs.getBigDecimal("rss_mb")));
    if (states.isEmpty()) {
      return new ServerState("", null, null, null, null, null);
    }
    return states.getFirst();
  }

  private ServerState withOnlinePlayerCount(ServerState state) {
    OffsetDateTime freshAfter = OffsetDateTime.now(ZoneOffset.UTC)
        .minus(properties.transaction().currentStateMaxAge());
    Integer onlinePlayers = jdbcTemplate.queryForObject("""
        select count(distinct coalesce(
          'PLAYER:' || player_id,
          'EOS:' || replace(cross_platform_id, 'EOS_', ''),
          'STEAM:' || replace(platform_id, 'Steam_', ''),
          'ENTITY:' || player_entity_id
        ))
        from t_player_current_state
        where online = true and last_updated >= ?
        """, Integer.class, freshAfter);
    return new ServerState(state.occurredAt(), state.fps(), onlinePlayers, state.zombieCount(),
        state.entityCount(), state.rssMb());
  }

  public Overview overview() {
    ServerState current = current();
    List<ServerMetricObservation> observations = jdbcTemplate.query("""
        select occurred_at, fps, zombie_count, entity_count, rss_mb
        from t_server_metric
        order by occurred_at desc
        limit 120
        """, (rs, rowNum) -> new ServerMetricObservation(
        rs.getObject("occurred_at", OffsetDateTime.class),
        rs.getBigDecimal("fps"),
        rs.getObject("zombie_count", Integer.class),
        rs.getObject("entity_count", Integer.class),
        rs.getBigDecimal("rss_mb")));
    List<ServerMetricPoint> history = observations.stream().map(row -> new ServerMetricPoint(
        displayTimeFormatter.format(row.occurredAt()), row.fps(), row.zombieCount(),
        row.entityCount(), row.rssMb())).toList();
    return new Overview(current, history, serverHealth(observations));
  }

  private ServerHealth serverHealth(List<ServerMetricObservation> newestFirst) {
    if (newestFirst.isEmpty()) {
      return new ServerHealth(
          "unknown", "観測待ち", 0, null, null, null, "", "", "", "", List.of());
    }
    List<ServerMetricObservation> chronological = new ArrayList<>(newestFirst);
    java.util.Collections.reverse(chronological);
    List<BigDecimal> fpsValues = chronological.stream().map(ServerMetricObservation::fps)
        .filter(java.util.Objects::nonNull).toList();
    BigDecimal averageFps = fpsValues.isEmpty() ? null : fpsValues.stream()
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(fpsValues.size()), 1, RoundingMode.HALF_UP);
    BigDecimal minimumFps = fpsValues.stream().min(BigDecimal::compareTo).orElse(null);
    BigDecimal maximumRss = chronological.stream().map(ServerMetricObservation::rssMb)
        .filter(java.util.Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
    long stableSamples = fpsValues.stream()
        .filter(fps -> fps.compareTo(BigDecimal.valueOf(18)) >= 0).count();
    long stability = fpsValues.isEmpty() ? 0 : stableSamples * 100 / fpsValues.size();
    List<ServerIncident> incidents = new ArrayList<>();
    ServerMetricObservation previous = null;
    for (ServerMetricObservation point : chronological) {
      List<String> reasons = new ArrayList<>();
      if (point.fps() != null && point.fps().compareTo(BigDecimal.valueOf(15)) < 0) {
        reasons.add("FPS低下 " + point.fps());
      }
      if (previous != null && point.rssMb() != null && previous.rssMb() != null
          && point.rssMb().subtract(previous.rssMb()).compareTo(BigDecimal.valueOf(256)) >= 0) {
        reasons.add("メモリ急増 +" + point.rssMb().subtract(previous.rssMb()).setScale(0, RoundingMode.HALF_UP) + "MB");
      }
      if (!reasons.isEmpty()) {
        incidents.add(new ServerIncident(
            displayTimeFormatter.format(point.occurredAt()), String.join(" / ", reasons)));
      }
      previous = point;
    }
    if (incidents.size() > 8) {
      incidents = incidents.subList(incidents.size() - 8, incidents.size());
    }
    String level = stability >= 90 && incidents.isEmpty() ? "stable"
        : stability >= 70 ? "watch" : "unstable";
    String label = switch (level) {
      case "stable" -> "安定稼働";
      case "watch" -> "一部変動あり";
      default -> "不安定区間あり";
    };
    BigDecimal fpsMax = fpsValues.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
    List<BigDecimal> rssValues = chronological.stream().map(ServerMetricObservation::rssMb)
        .filter(java.util.Objects::nonNull).toList();
    BigDecimal rssMax = rssValues.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
    return new ServerHealth(
        level, label, stability, averageFps, minimumFps, maximumRss,
        metricChartPoints(chronological, fpsMax, ServerMetricObservation::fps),
        metricChartPoints(chronological, rssMax, ServerMetricObservation::rssMb),
        displayTimeFormatter.format(chronological.getFirst().occurredAt()),
        displayTimeFormatter.format(chronological.getLast().occurredAt()),
        List.copyOf(incidents));
  }

  private String metricChartPoints(
      List<ServerMetricObservation> rows, BigDecimal max,
      java.util.function.Function<ServerMetricObservation, BigDecimal> value) {
    int lastIndex = Math.max(1, rows.size() - 1);
    return java.util.stream.IntStream.range(0, rows.size()).mapToObj(index -> {
      BigDecimal metric = value.apply(rows.get(index));
      double x = index * 300.0 / lastIndex;
      double y = metric == null ? 76 : 76 - metric.doubleValue() * 68 / max.max(BigDecimal.ONE).doubleValue();
      return String.format(java.util.Locale.ROOT, "%.1f,%.1f", x, y);
    }).collect(java.util.stream.Collectors.joining(" "));
  }

  public record Overview(ServerState current, List<ServerMetricPoint> history, ServerHealth health) {
  }

  public record ServerMetricPoint(
      String occurredAt,
      BigDecimal fps,
      Integer zombieCount,
      Integer entityCount,
      BigDecimal rssMb
  ) {
  }

  public record ServerHealth(
      String level, String label, long stabilityPercent,
      BigDecimal averageFps, BigDecimal minimumFps, BigDecimal maximumRss,
      String fpsChartPoints, String rssChartPoints, String firstObservedAt,
      String lastObservedAt, List<ServerIncident> incidents) {
  }

  public record ServerIncident(String occurredAt, String reason) {
  }

  private record ServerMetricObservation(
      OffsetDateTime occurredAt, BigDecimal fps, Integer zombieCount,
      Integer entityCount, BigDecimal rssMb) {
  }

  public record ServerState(
      String occurredAt,
      BigDecimal fps,
      Integer playerCount,
      Integer zombieCount,
      Integer entityCount,
      BigDecimal rssMb
  ) {
  }
}
