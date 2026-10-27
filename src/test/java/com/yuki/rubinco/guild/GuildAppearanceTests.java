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
        .containsSubsequence("body-core", "head-soft", "face-m", "expression-resolute", "age-elder", "outfit-knight", "hair-short", "acc-glasses", "hat-knight");
    assertThat(look.layers(18, "陽気な旅人。")).contains("expression-bright", "age-young");
    assertThat(look.layers(40, "慎重な話し方。")).contains("expression-thoughtful", "age-mature");
    assertThat(look.layers(28, "穏やか。")).contains("expression-gentle").doesNotContain("age-mature", "age-elder", "age-young");
    assertThat(look.layers(null, null)).isEqualTo(look.layers());
    assertThat(look.layers(28, "旅を好む。")).isEqualTo(look.layers());
    assertThat(look.layers(65, "勇敢で頑固な騎士。")).isEqualTo(look.layers(65, "勇敢で頑固な騎士。"));
    assertThat(look.style()).isEqualTo(colours);
  }

  private static java.util.Set<String> drawn(String resource, String viewport) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    try (var input = new ClassPathResource(resource).getInputStream()) {
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
        assertThat(attributes.getNamedItem("viewBox").getNodeValue()).isEqualTo(viewport);
      }
      return ids;
    }
  }

  @Test void everySavedPartAndNewFaceLayerHasAnSdSymbolWithTheSameViewport() throws Exception {
    var ids = drawn("static/img/guild-people.svg", "0 0 96 144");
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

  @Test void everyPoseAndPortraitUsesDrawnPartsWithMatchingIdentityChoices() throws Exception {
    var sd = drawn("static/img/guild-people.svg", "0 0 96 144");
    var bust = drawn("static/img/guild-portraits.svg", "0 0 360 440");
    var outfits = java.util.stream.Stream.concat(GuildPeople.OUTFITS.values().stream(), GuildPeople.WOMEN.values().stream()).distinct().toList();
    for (String outfit : outfits) {
      for (String variant : GuildPeople.OUTFIT_VARIANTS) {
        var look = GuildPeople.Look.of(outfit, "sidebraid", "#6e4630", "#e6b48e", "#5f7d4a", "#3a4f6a", "女性",
            "glasses", "#3f6b3a", "almond", "arched", variant);
        for (String contour : GuildPeople.HEAD_SHAPES) {
          for (String pose : GuildPeople.POSES) {
            var figure = look.layers(65, "穏やか", contour, pose);
            var portrait = look.portraitLayers(65, "穏やか", contour, pose);
            assertThat(sd).containsAll(figure);
            assertThat(bust).containsAll(portrait);
            assertThat(portrait).contains("head-" + contour, "eyes-almond", "brows-arched", "hair-sidebraid", "hair-back-sidebraid", "acc-glasses", "age-elder", "pose-" + outfit + "-" + pose)
                .doesNotContain("legs-skirt", "legs-pants");
            if (!pose.equals("rest")) assertThat(figure).contains("body-posed", "outfit-" + outfit + "-posed").doesNotContain("body-core", "outfit-" + outfit);
          }
        }
      }
    }
    for (String hair : java.util.stream.Stream.concat(GuildPeople.MALE_HAIR.stream(), GuildPeople.FEMALE_HAIR.stream()).toList()) {
      assertThat(bust).contains("hair-" + hair, "hair-back-" + hair);
    }
    for (String eye : GuildPeople.EYE_STYLES) assertThat(bust).contains("eyes-" + eye);
    assertThat(bust).contains("resident");
  }

  @Test void activityAndPersonalitySelectGesturesWithoutChangingStoredIdentity() {
    var look = look("adventurer_f", "braid", "glasses", "女性");
    var resident = new GuildService.Character(41, java.time.LocalDate.of(2026, 10, 20), "terraria", "リネ", "紹介", "由来", "慎重", "moss", "ALIVE",
        28, "女性", "冒険者", null, null, null, look, null, "グランフォード", "グランフォード", "無所属", "無信仰");
    assertThat(resident.pose()).isEqualTo("think");
    assertThat(new GuildService.Visit(resident, 1, "一杯飲んでいる").pose()).isEqualTo("mug");
    assertThat(new GuildService.Visit(resident, 1, "客に挨拶している").pose()).isEqualTo("wave");
    assertThat(GuildPeople.pose("腕組みしている", "rest")).isEqualTo("crossed");
    assertThat(GuildPeople.pose(null, "rest")).isEqualTo("rest");
    assertThat(resident.portraitLayers()).contains("head-" + resident.contour(), "hair-braid", "acc-glasses", "pose-adventurer_f-think");
    assertThat(resident.spriteLayers("mug")).contains("head-" + resident.contour(), "hair-braid", "acc-glasses", "pose-adventurer_f-mug");
    assertThat(resident.look()).isSameAs(look);
    assertThat(look.layers(28, "慎重", "invalid", "invalid")).contains("head-soft", "body-core");
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
