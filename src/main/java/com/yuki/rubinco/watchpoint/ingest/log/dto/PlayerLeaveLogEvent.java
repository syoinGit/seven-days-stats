package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.time.OffsetDateTime;

public record PlayerLeaveLogEvent(
    OffsetDateTime occurredAt,
    String playerName,
    int playerEntityId,
    String platformId,
    String crossPlatformId,
    Integer clientNumber,
    String rawLine
) {
}
