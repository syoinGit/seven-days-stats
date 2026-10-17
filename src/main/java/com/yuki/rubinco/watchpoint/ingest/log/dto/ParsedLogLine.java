package com.yuki.rubinco.watchpoint.ingest.log.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ParsedLogLine(
    OffsetDateTime occurredAt,
    BigDecimal serverUptimeSeconds,
    String level,
    String message,
    String rawLine
) {
}
