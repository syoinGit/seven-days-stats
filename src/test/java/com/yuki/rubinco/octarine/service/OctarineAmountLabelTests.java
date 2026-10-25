package com.yuki.rubinco.octarine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuki.rubinco.octarine.names.ItemCategory;
import com.yuki.rubinco.octarine.service.OctarineQueryService.ChestChange;
import com.yuki.rubinco.octarine.service.OctarineQueryService.ItemChange;
import com.yuki.rubinco.octarine.service.OctarineQueryService.ItemRow;
import org.junit.jupiter.api.Test;

/** Equipment stores durability in the amount; pages count it in pieces (one per slot). */
class OctarineAmountLabelTests {
  @Test
  void equipmentIsCountedInPiecesNotDurability() {
    var armor = new ItemRow(8648, "調理なべの桶", "CookingPotBreastArmor", 200, 1, 1, "", ItemCategory.ARMOR);
    var wood = new ItemRow(1, "木材", "Wood", 200, 3, 1, "", ItemCategory.MATERIAL);
    assertThat(armor.count()).isEqualTo(1);
    assertThat(wood.count()).isEqualTo(200);
  }

  @Test
  void itemChangesShowPiecesForEquipment() {
    var added = new ItemChange("", 200, 400, 200, 1, 2, true);
    assertThat(added.label()).isEqualTo("1個 → 2個（耐久の合計 200 → 400）");
    assertThat(added.deltaLabel()).isEqualTo("+1個");
    assertThat(added.up()).isTrue();
    var worn = new ItemChange("", 200, 180, -20, 1, 1, true);
    assertThat(worn.deltaLabel()).isEqualTo("耐久 -20");
    assertThat(worn.up()).isFalse();
    var stack = new ItemChange("", 3, 5, 2, 1, 1, false);
    assertThat(stack.label()).isEqualTo("3 → 5（1枠 → 1枠）");
    assertThat(stack.deltaLabel()).isEqualTo("+2");
  }

  @Test
  void chestChangesUseSlotsWhenStoredAndDurabilityForOlderRows() {
    var taken = new ChestChange("", "調理なべの桶", 8648, 200, 0, -200, 1, 0, true);
    assertThat(taken.label()).isEqualTo("1個 → 0個");
    assertThat(taken.deltaLabel()).isEqualTo("-1個");
    assertThat(taken.up()).isFalse();
    var older = new ChestChange("", "調理なべの桶", 8648, 0, 200, 200, null, null, true);
    assertThat(older.label()).isEqualTo("耐久 0 → 200");
    assertThat(older.deltaLabel()).isEqualTo("耐久 +200");
    var stack = new ChestChange("", "木材", 1, 3, 5, 2, 1, 1, false);
    assertThat(stack.label()).isEqualTo("3 → 5");
    assertThat(stack.deltaLabel()).isEqualTo("+2");
  }
}
