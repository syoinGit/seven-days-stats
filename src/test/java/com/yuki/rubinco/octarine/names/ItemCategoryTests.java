package com.yuki.rubinco.octarine.names;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ItemCategoryTests {
  @Test
  void categoriesFollowTheGamesObjectTypeAndTags() {
    assertThat(ItemCategory.of(500, Set.of(25, 34))).isEqualTo(ItemCategory.WEAPON);
    assertThat(ItemCategory.of(100, Set.of(19))).isEqualTo(ItemCategory.ARMOR);
    assertThat(ItemCategory.of(603, Set.of())).isEqualTo(ItemCategory.TOOL);
    assertThat(ItemCategory.of(1100, Set.of(23, 24))).isEqualTo(ItemCategory.FOOD);
    assertThat(ItemCategory.of(0, Set.of(11))).isEqualTo(ItemCategory.ORE);
    assertThat(ItemCategory.of(0, Set.of())).isEqualTo(ItemCategory.MATERIAL);
    assertThat(ItemCategory.of(800, Set.of(25, 36))).isEqualTo(ItemCategory.WEAPON);
    assertThat(ItemCategory.of(800, Set.of(25))).isEqualTo(ItemCategory.PLACEABLE);
    assertThat(ItemCategory.of(null, Set.of())).isEqualTo(ItemCategory.OTHER);
    assertThat(ItemCategory.WEAPON.durable()).isTrue();
    assertThat(ItemCategory.MATERIAL.durable()).isFalse();
  }
}
