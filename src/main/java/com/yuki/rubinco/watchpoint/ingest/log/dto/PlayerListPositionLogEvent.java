package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record PlayerListPositionLogEvent(
    OffsetDateTime occurredAt,
    List<PlayerPosition> players,
    int totalPlayerCount,
    List<String> rawLines,
    int consumedLineCount
) {

  public record PlayerPosition(
      String playerName,
      int playerEntityId,
      int positionX,
      Integer positionY,
      int positionZ,
      BigDecimal rotationX,
      BigDecimal rotationY,
      BigDecimal rotationZ,
      Integer health,
      Integer deaths,
      Integer zombies,
      Integer players,
      Integer score,
      Integer level,
      String platformId,
      String crossPlatformId,
      Integer ping,
      String rawLine
  ) {
  }
}
