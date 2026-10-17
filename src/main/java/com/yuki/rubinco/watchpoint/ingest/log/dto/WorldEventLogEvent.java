package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.time.OffsetDateTime;

public record WorldEventLogEvent(
    OffsetDateTime occurredAt,
    String eventType,
    String actorPlayerName,
    Integer actorPlayerEntityId,
    String detailText,
    Integer positionX,
    Integer positionY,
    Integer positionZ,
    Integer targetPositionX,
    Integer targetPositionY,
    Integer targetPositionZ,
    String rawLine
) {
}
