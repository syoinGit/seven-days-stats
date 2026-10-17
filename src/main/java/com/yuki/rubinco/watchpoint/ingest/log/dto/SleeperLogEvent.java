package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.time.OffsetDateTime;

public record SleeperLogEvent(
    OffsetDateTime occurredAt,
    String transactionType,
    int sleeperVolumeX,
    int sleeperVolumeY,
    int sleeperVolumeZ,
    int positionX,
    int positionY,
    int positionZ,
    Integer chunkX,
    Integer chunkZ,
    String sleeperGroup,
    String entityClass,
    Integer entityCount,
    String rawLine
) {
}
