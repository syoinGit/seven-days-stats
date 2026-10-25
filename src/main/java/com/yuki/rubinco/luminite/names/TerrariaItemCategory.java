package com.yuki.rubinco.luminite.names;

/**
 * Site categories for items, written into items.tsv by scripts/terraria/add-item-categories.py
 * from the game's own item numbers. Items without that column are OTHER.
 */
public enum TerrariaItemCategory {
  WEAPON("武器"),
  AMMO("弾薬"),
  TOOL("道具"),
  ARMOR("防具"),
  ACCESSORY("アクセサリー"),
  VANITY("見た目"),
  POTION("ポーション・料理"),
  BLOCK("ブロック・壁"),
  PLACEABLE("家具・設置物"),
  ORE("鉱石・インゴット"),
  MATERIAL("素材"),
  OTHER("その他");

  private final String label;

  TerrariaItemCategory(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /** The icon symbol id in /img/luminite-icons.svg, also used in URLs. */
  public String icon() {
    return name().toLowerCase();
  }

  public static TerrariaItemCategory of(String key) {
    if (key == null) return OTHER;
    for (TerrariaItemCategory category : values()) {
      if (category.icon().equals(key.strip())) return category;
    }
    return OTHER;
  }
}
