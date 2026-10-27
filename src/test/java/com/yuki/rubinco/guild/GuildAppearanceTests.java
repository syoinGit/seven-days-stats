package com.yuki.rubinco.guild;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class GuildAppearanceTests {
  private static GuildPeople.Look look(String outfit, String hair, String accessory, String gender) {
    return GuildPeople.Look.of(outfit, hair, "#6e4630", "#e6b48e", "#5f7d4a", "#3a4f6a", gender, accessory, "#3f6b3a");
  }

  @Test void temperamentAndAgeStayBelowHairAndAccessoriesWithoutChangingSavedColours() {
    var look = look("knight", "short", "glasses", "男性");
    String colours = look.style();
    assertThat(look.layers(65, "勇敢で頑固な騎士。"))
        .containsSubsequence("body", "face-m", "expression-resolute", "age-elder", "outfit-knight", "hair-short", "acc-glasses", "hat-knight");
    assertThat(look.layers(18, "陽気な旅人。")).contains("expression-bright", "age-young");
    assertThat(look.layers(40, "慎重な話し方。")).contains("expression-thoughtful", "age-mature");
    assertThat(look.layers(28, "穏やか。")).contains("expression-gentle").doesNotContain("age-mature", "age-elder", "age-young");
    assertThat(look.layers(null, null)).isEqualTo(look.layers());
    assertThat(look.layers(28, "旅を好む。")).isEqualTo(look.layers());
    assertThat(look.layers(65, "勇敢で頑固な騎士。")).isEqualTo(look.layers(65, "勇敢で頑固な騎士。"));
    assertThat(look.style()).isEqualTo(colours);
  }

  @Test void everySavedPartAndNewFaceLayerHasAnSdSymbolWithTheSameViewport() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    try (var input = new ClassPathResource("static/img/guild-people.svg").getInputStream()) {
      var document = factory.newDocumentBuilder().parse(input);
      var symbols = document.getElementsByTagName("symbol");
      var definitions = new HashSet<String>();
      var references = new HashSet<String>();
      var elements = document.getElementsByTagName("*");
      for (int i = 0; i < elements.getLength(); i++) {
        var attributes = elements.item(i).getAttributes();
        var id = attributes.getNamedItem("id");
        if (id != null) assertThat(definitions.add(id.getNodeValue())).as("unique SVG id: %s", id.getNodeValue()).isTrue();
        for (int j = 0; j < attributes.getLength(); j++) {
          var urls = java.util.regex.Pattern.compile("url\\(#([^)]*)\\)").matcher(attributes.item(j).getNodeValue());
          while (urls.find()) references.add(urls.group(1));
        }
      }
      assertThat(definitions).containsAll(references);
      var ids = new HashSet<String>();
      for (int i = 0; i < symbols.getLength(); i++) {
        var attributes = symbols.item(i).getAttributes();
        assertThat(ids.add(attributes.getNamedItem("id").getNodeValue())).isTrue();
        assertThat(attributes.getNamedItem("viewBox").getNodeValue()).isEqualTo("0 0 96 144");
      }
      for (String gender : GuildPeople.GENDERS) {
        for (String outfit : GuildPeople.OUTFITS.values()) {
          if (gender.equals("女性")) outfit = GuildPeople.WOMEN.getOrDefault(outfit, outfit);
          for (String hair : gender.equals("女性") ? GuildPeople.FEMALE_HAIR : GuildPeople.MALE_HAIR) {
            for (String accessory : gender.equals("女性") ? GuildPeople.FEMALE_ACCESSORIES : GuildPeople.MALE_ACCESSORIES) {
              assertThat(ids).containsAll(look(outfit, hair, accessory, gender).layers());
            }
          }
        }
      }
      assertThat(ids).containsAll(List.of("resident", "expression-bright", "expression-gentle", "expression-resolute",
          "expression-thoughtful", "age-young", "age-mature", "age-elder"));
      for (String eyes : GuildPeople.EYE_STYLES) assertThat(ids).contains("eyes-" + eyes);
      for (String brow : GuildPeople.BROW_STYLES) assertThat(ids).contains("brows-" + brow);
      for (String outfit : java.util.stream.Stream.concat(GuildPeople.OUTFITS.values().stream(), GuildPeople.WOMEN.values().stream()).toList()) {
        for (String variant : List.of("travel", "formal")) assertThat(ids).contains("dress-" + outfit + "-" + variant);
      }
    }
  }

  @Test void missingVariantColumnsUseCompatibleDefaultsAndUnknownPartsAreRejected() {
    var old = look("knight", "short", "glasses", "男性");
    assertThat(old.eyeStyle()).isEqualTo("round");
    assertThat(old.browStyle()).isEqualTo("natural");
    assertThat(old.outfitVariant()).isEqualTo("classic");
    assertThat(GuildPeople.Look.of("knight", "short", "#6e4630", "#e6b48e", "#5f7d4a", "#3a4f6a", "男性",
        "glasses", "#3f6b3a", "unknown", "natural", "classic")).isNull();
  }

  @Test void migrationDiversifiesExistingEyesWithoutChangingOldPartsOrLegacyRows() {
    var data = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:appearanceMigration;MODE=PostgreSQL", "sa", "");
    // Keep the connection open: the minimal fixture needs only the columns this migration uses.
    try (var connection = data.getConnection()) {
      var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
      jdbc.execute("CREATE TABLE M_GUILD_CHARACTER (id BIGINT PRIMARY KEY, outfit VARCHAR(20), hair_style VARCHAR(20), skin_color VARCHAR(7))");
      for (int i = 1; i <= 9; i++) jdbc.update("INSERT INTO M_GUILD_CHARACTER VALUES (?, 'knight', 'short', '#e6b48e')", i);
      jdbc.execute("INSERT INTO M_GUILD_CHARACTER VALUES (10, NULL, NULL, NULL)");
      new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new ClassPathResource("db/migration/V70__guild_look_variants.sql"))
          .execute(jdbc.getDataSource());
      assertThat(jdbc.queryForList("SELECT DISTINCT eye_style FROM M_GUILD_CHARACTER WHERE outfit IS NOT NULL", String.class))
          .containsExactlyInAnyOrderElementsOf(GuildPeople.EYE_STYLES);
      assertThat(jdbc.queryForList("SELECT hair_style FROM M_GUILD_CHARACTER WHERE outfit IS NOT NULL", String.class)).containsOnly("short");
      assertThat(jdbc.queryForList("SELECT skin_color FROM M_GUILD_CHARACTER WHERE outfit IS NOT NULL", String.class)).containsOnly("#e6b48e");
      assertThat(jdbc.queryForObject("SELECT outfit FROM M_GUILD_CHARACTER WHERE id = 10", String.class)).isNull();
      assertThat(jdbc.queryForObject("SELECT eye_style FROM M_GUILD_CHARACTER WHERE id = 10", String.class)).isEqualTo("round");
    } catch (java.sql.SQLException exception) {
      throw new AssertionError(exception);
    }
  }
}
