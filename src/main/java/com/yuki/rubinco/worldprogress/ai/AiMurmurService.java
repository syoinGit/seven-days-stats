package com.yuki.rubinco.worldprogress.ai;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The AI's murmur: once per interval across every game, from one server picked at random among those that are
 * online with someone connected. Nobody on anywhere: no murmur. A server with nothing to talk about hands the
 * turn to the next one.
 */
@Service
@Slf4j
public class AiMurmurService {
  private final WorldAiProperties properties;
  private final List<AiMurmurSource> sources;
  private final ServerActivityGate gate;

  public AiMurmurService(WorldAiProperties properties, List<AiMurmurSource> sources, WorldAiPublishingService worlds,
      ServerActivityGate gate) {
    this.properties = properties;
    List<AiMurmurSource> all = new ArrayList<>(sources);
    all.addAll(worlds.sources());
    this.sources = List.copyOf(all);
    this.gate = gate;
  }

  public enum Status { PUBLISHED, NOT_DUE, NOBODY_ONLINE, NO_DATA, FAILED }

  /** gameId is the server that spoke (or failed); null otherwise. */
  public record Result(Status status, String gameId) { }

  public Result publishDue() {
    OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.interval());
    boolean recent = sources.stream().map(AiMurmurSource::lastPublished).flatMap(Optional::stream)
        .anyMatch(at -> at.isAfter(threshold));
    if (recent) return new Result(Status.NOT_DUE, null);
    List<AiMurmurSource> candidates = new ArrayList<>(sources.stream()
        .filter(AiMurmurSource::enabled)
        .filter(source -> gate.aiAllowed(source.gameId()))
        .filter(source -> source.onlineCount() > 0)
        .toList());
    if (candidates.isEmpty()) return new Result(Status.NOBODY_ONLINE, null);
    Collections.shuffle(candidates, ThreadLocalRandom.current());
    for (AiMurmurSource source : candidates) {
      switch (source.murmur()) {
        case PUBLISHED -> {
          log.info("AI murmur published. gameId={}", source.gameId());
          return new Result(Status.PUBLISHED, source.gameId());
        }
        case FAILED -> {
          return new Result(Status.FAILED, source.gameId());
        }
        case NO_DATA -> { }
      }
    }
    return new Result(Status.NO_DATA, null);
  }
}
