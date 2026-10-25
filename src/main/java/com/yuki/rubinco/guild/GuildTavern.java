package com.yuki.rubinco.guild;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where today's visitors are in the tavern. The room is four places seen one at a time: the counter, the tables,
 * the quest board and the hearth. Residents who talked today share a table (the newest talk at the first table);
 * everyone else spreads over the four places in an order that changes with the part of the day.
 * Nothing is stored: the same day, phase and talks always give the same room.
 */
public final class GuildTavern {
  public static final List<String> KEYS = List.of("counter", "tables", "board", "hearth");
  private static final Map<String, String> NAMES = Map.of("counter", "カウンター", "tables", "テーブル席", "board", "依頼掲示板", "hearth", "暖炉");
  // The tables hold two parties of three (slots 0-2 and 3-5); fifteen places for at most ten visitors.
  private static final Map<String, Integer> CAPACITY = Map.of("counter", 3, "tables", 6, "board", 3, "hearth", 3);

  public record Seat(GuildService.Visit visit, int slot) { }

  public record Area(String key, String name, List<Seat> seats, boolean talk) {
    public int count() {
      return seats.size();
    }
  }

  public record Layout(List<Area> areas, String current) {
    public Area area(String key) {
      return areas.stream().filter(area -> area.key().equals(key)).findFirst().orElseThrow();
    }
  }

  private GuildTavern() { }

  /** groups: today's talks, newest first, as participant ids. talking: who speaks in the current scene. */
  public static Layout arrange(List<GuildService.Visit> visits, List<List<Long>> groups, Set<Long> talking, LocalDate date, String phase) {
    Map<Long, GuildService.Visit> present = new LinkedHashMap<>();
    visits.forEach(visit -> present.put(visit.character().id(), visit));
    Map<String, GuildService.Visit[]> places = new LinkedHashMap<>();
    KEYS.forEach(key -> places.put(key, new GuildService.Visit[CAPACITY.get(key)]));
    Set<Long> placed = new HashSet<>();
    int table = 0;
    for (List<Long> group : groups) {
      if (table == 2) break;
      var here = group.stream().filter(present::containsKey).filter(id -> !placed.contains(id)).distinct().limit(3).toList();
      if (here.size() < 2) continue;
      for (int i = 0; i < here.size(); i++) places.get("tables")[table * 3 + i] = present.get(here.get(i));
      placed.addAll(here);
      table++;
    }
    var rest = present.values().stream().filter(visit -> !placed.contains(visit.character().id()))
        .sorted(Comparator.comparingLong(visit -> mix(visit.character().id(), date, phase))).toList();
    for (var visit : rest) {
      int start = (int) Math.floorMod(mix(visit.character().id(), date, phase + "/place"), (long) KEYS.size());
      for (int i = 0; i < KEYS.size(); i++) {
        if (put(places.get(KEYS.get((start + i) % KEYS.size())), visit)) break;
      }
    }
    List<Area> areas = new ArrayList<>();
    for (String key : KEYS) {
      List<Seat> seats = new ArrayList<>();
      var slots = places.get(key);
      for (int slot = 0; slot < slots.length; slot++) if (slots[slot] != null) seats.add(new Seat(slots[slot], slot));
      areas.add(new Area(key, NAMES.get(key), List.copyOf(seats), seats.stream().anyMatch(seat -> talking.contains(seat.visit().character().id()))));
    }
    String current = areas.stream().filter(Area::talk).map(Area::key).findFirst()
        .orElse(areas.getFirst().count() > 0 ? "counter" : areas.stream().filter(area -> area.count() > 0).map(Area::key).findFirst().orElse("counter"));
    return new Layout(List.copyOf(areas), current);
  }

  private static boolean put(GuildService.Visit[] slots, GuildService.Visit visit) {
    for (int slot = 0; slot < slots.length; slot++) {
      if (slots[slot] == null) {
        slots[slot] = visit;
        return true;
      }
    }
    return false;
  }

  private static long mix(long id, LocalDate date, String salt) {
    long x = id * 0x9E3779B97F4A7C15L ^ date.toEpochDay() * 0xC2B2AE3D27D4EB4FL ^ salt.hashCode() * 0x165667B19E3779F9L;
    x ^= x >>> 31;
    x *= 0xBF58476D1CE4E5B9L;
    return x ^ x >>> 29;
  }
}
