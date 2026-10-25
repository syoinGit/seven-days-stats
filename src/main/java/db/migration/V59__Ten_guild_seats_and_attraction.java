package db.migration;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * The tavern grows to ten seats: V52 limited T_GUILD_VISIT.seat to 0..4 with an inline check whose name
 * differs between PostgreSQL and H2, so look it up. And a resident may find another their type
 * (T_GUILD_RELATION.smitten, one direction only): existing relationships between adults of different
 * genders get it at the same chance new ones do.
 */
public class V59__Ten_guild_seats_and_attraction extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    List<String> names = new ArrayList<>();
    try (Statement statement = context.getConnection().createStatement();
         ResultSet rows = statement.executeQuery("SELECT tc.constraint_name FROM information_schema.table_constraints tc "
             + "WHERE UPPER(tc.table_name) = 'T_GUILD_VISIT' AND tc.constraint_type = 'CHECK'")) {
      while (rows.next()) names.add(rows.getString(1));
    }
    try (Statement statement = context.getConnection().createStatement()) {
      for (String name : names) {
        // Only the seat range: NOT NULL checks (PostgreSQL lists some as CHECK) are left alone.
        if (name.toUpperCase().contains("NOT_NULL") || name.toUpperCase().contains("NOTNULL")) continue;
        statement.execute("ALTER TABLE T_GUILD_VISIT DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
      }
      statement.execute("ALTER TABLE T_GUILD_VISIT ADD CONSTRAINT ck_guild_visit_seat CHECK (seat BETWEEN 0 AND 9)");
      statement.execute("ALTER TABLE T_GUILD_RELATION ADD COLUMN smitten BOOLEAN NOT NULL DEFAULT FALSE");
      statement.execute("""
          UPDATE T_GUILD_RELATION SET smitten = TRUE WHERE RANDOM() < 0.2 AND EXISTS (
            SELECT 1 FROM M_GUILD_CHARACTER f, M_GUILD_CHARACTER t
            WHERE f.id = T_GUILD_RELATION.from_id AND t.id = T_GUILD_RELATION.to_id
              AND f.gender IS NOT NULL AND t.gender IS NOT NULL AND f.gender <> t.gender AND f.age >= 18 AND t.age >= 18)
          """);
    }
  }
}
