package com.yuki.rubinco.shared;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formats database timestamps consistently for the web UI and social feed. */
public final class DisplayTimeFormatter {

  public static final ZoneId JST = ZoneId.of("Asia/Tokyo");
  private static final DateTimeFormatter DISPLAY_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  public String format(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof OffsetDateTime dateTime) {
      return dateTime.atZoneSameInstant(JST).format(DISPLAY_FORMAT);
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime().format(DISPLAY_FORMAT);
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime.format(DISPLAY_FORMAT);
    }
    return value.toString().replace('T', ' ');
  }
}
