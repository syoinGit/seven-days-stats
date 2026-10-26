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
      var symbols = factory.newDocumentBuilder().parse(input).getElementsByTagName("symbol");
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
    }
  }
}
