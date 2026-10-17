package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.time.OffsetDateTime;

public record EntityKillLogEvent(
    OffsetDateTime occurredAt,
    String playerName,
    int playerEntityId,
    String targetEntityType,
    int targetEntityId,
    String rawLine
) {
}
