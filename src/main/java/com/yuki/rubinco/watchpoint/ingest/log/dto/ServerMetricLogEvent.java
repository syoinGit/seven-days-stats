package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ServerMetricLogEvent(
    OffsetDateTime occurredAt,
    BigDecimal uptimeMinutes,
    BigDecimal fps,
    BigDecimal heapMb,
    BigDecimal maxHeapMb,
    Integer chunks,
    Integer cgo,
    Integer playerCount,
    Integer zombieCount,
    Integer entityCount,
    Integer entityCountDetail,
    Integer itemCount,
    Integer co,
    BigDecimal rssMb,
    String rawLine
) {
}
