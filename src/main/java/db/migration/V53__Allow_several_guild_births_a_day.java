package db.migration;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V52 made born_on unique (one resident a day). Admins may now add several a day, so drop that
 * constraint. It was declared inline, so its name differs between PostgreSQL and H2: look it up.
 */
public class V53__Allow_several_guild_births_a_day extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    List<String> names = new ArrayList<>();
    try (Statement statement = context.getConnection().createStatement();
         ResultSet rows = statement.executeQuery("SELECT constraint_name FROM information_schema.table_constraints "
             + "WHERE UPPER(table_name) = 'M_GUILD_CHARACTER' AND constraint_type = 'UNIQUE'")) {
      while (rows.next()) names.add(rows.getString(1));
    }
    try (Statement statement = context.getConnection().createStatement()) {
      for (String name : names) {
        statement.execute("ALTER TABLE M_GUILD_CHARACTER DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
      }
      statement.execute("CREATE INDEX idx_m_guild_character_born_on ON M_GUILD_CHARACTER (born_on)");
    }
  }
}
