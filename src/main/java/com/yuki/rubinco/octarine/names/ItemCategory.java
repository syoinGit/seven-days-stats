package com.yuki.rubinco.octarine.names;

import java.util.Set;

/**
 * Site categories for items, decided from the game's ObjectType and ObjectCategoryTag numbers in
 * the names table (scripts/corekeeper/add-object-types.py). Objects without that data are OTHER.
 */
public enum ItemCategory {
  WEAPON("武器"),
  ARMOR("防具"),
  TOOL("道具"),
  FOOD("食べ物"),
  ORE("鉱石"),
  MATERIAL("素材"),
  PLACEABLE("設置物"),
  OTHER("その他");

  private static final int TAG_ORE = 11;
  private static final int TAG_BOMB = 36;

  private final String label;

  ItemCategory(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /** The icon symbol id in /img/octarine-icons.svg. */
  public String icon() {
    return name().toLowerCase();
  }

  /** Weapons, armour and tools store remaining durability where other items store a count. */
  public boolean durable() {
    return this == WEAPON || this == ARMOR || this == TOOL;
  }

  public static ItemCategory of(Integer type, Set<Integer> tags) {
    if (tags.contains(TAG_ORE)) return ORE;
    if (tags.contains(TAG_BOMB)) return WEAPON;
    if (type == null) return OTHER;
    return switch (type) {
      case 500, 501, 502, 503, 610 -> WEAPON;
      case 100, 101, 102, 103, 104, 105, 106, 107, 108 -> ARMOR;
      case 600, 601, 602, 603, 604, 605, 606, 607, 608, 609, 1200, 1201, 1250, 1600 -> TOOL;
      case 1100 -> FOOD;
      case 0, 1300, 1400 -> MATERIAL;
      case 800 -> PLACEABLE;
      default -> OTHER;
    };
  }
}
