package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.time.OffsetDateTime;

public record VehicleLogEvent(
    OffsetDateTime occurredAt,
    String eventType,
    int vehicleEntityId,
    String vehicleType,
    String vehicleName,
    String ownerCrossPlatformId,
    Integer positionX,
    Integer positionY,
    Integer positionZ,
    String removalReason,
    String rawLine
) {
}
