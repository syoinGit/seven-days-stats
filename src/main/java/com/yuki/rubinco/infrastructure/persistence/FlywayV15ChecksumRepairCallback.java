package com.yuki.rubinco.infrastructure.persistence;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/**
 * Repairs one known production checksum mismatch caused when V15 was edited after deployment.
 * The exact old checksum guard keeps this from masking unrelated migration corruption.
 */
@Slf4j
@Component
public class FlywayV15ChecksumRepairCallback implements Callback {

  static final int APPLIED_V15_CHECKSUM = -1037278684;
  static final int CURRENT_V15_CHECKSUM = -853613223;
  static final String APPLIED_V15_DESCRIPTION = "backfill xp player from recent kills";
  static final String CURRENT_V15_DESCRIPTION = "add dashboard query indexes";

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_VALIDATE;
  }

  @Override
  public boolean canHandleInTransaction(Event event, Context context) {
    return true;
  }

  @Override
  public void handle(Event event, Context context) {
    String table = context.getConfiguration().getTable();
    if (!table.matches("[A-Za-z0-9_]+") || !tableExists(context, table)) {
      return;
    }
    String sql = "update \"" + table + "\" set \"checksum\" = ?, \"description\" = ? "
        + "where \"version\" = ? and \"description\" = ? "
        + "and \"checksum\" in (?, ?)";
    try (PreparedStatement statement = context.getConnection().prepareStatement(sql)) {
      statement.setInt(1, CURRENT_V15_CHECKSUM);
      statement.setString(2, CURRENT_V15_DESCRIPTION);
      statement.setString(3, "15");
      statement.setString(4, APPLIED_V15_DESCRIPTION);
      statement.setInt(5, APPLIED_V15_CHECKSUM);
      statement.setInt(6, CURRENT_V15_CHECKSUM);
      if (statement.executeUpdate() == 1) {
        log.warn("Repaired the known Flyway V15 checksum and description mismatch");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to repair the known Flyway V15 checksum mismatch", e);
    }
  }

  private boolean tableExists(Context context, String table) {
    try {
      DatabaseMetaData metadata = context.getConnection().getMetaData();
      try (ResultSet tables = metadata.getTables(null, null, "%", new String[] {"TABLE"})) {
        while (tables.next()) {
          if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
            return true;
          }
        }
      }
      return false;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to inspect the Flyway schema history table", e);
    }
  }

  @Override
  public String getCallbackName() {
    return "repair-known-v15-checksum";
  }
}
