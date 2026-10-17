package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.time.OffsetDateTime;

public record PlayerJoinLogEvent(
    OffsetDateTime occurredAt,
    String playerName,
    int playerEntityId,
    String platformId,
    String crossPlatformId,
    Integer positionX,
    Integer positionY,
    Integer positionZ,
    String reason,
    Integer clientNumber,
    String rawLine
) {
}
