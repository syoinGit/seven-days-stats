package com.yuki.rubinco.watchpoint.ingest;

public record SevenDaysDataImportResult(
    long serverSettings,
    long gameConfigElements,
    long japaneseTranslations,
    long gameEntities,
    long blocks,
    long items,
    long vehicles,
    long worlds,
    long gameSaves,
    long worldPois,
    long worldSpawnPoints,
    long players,
    long playerStateSnapshots,
    long playerMarkerSnapshots
) {
}
