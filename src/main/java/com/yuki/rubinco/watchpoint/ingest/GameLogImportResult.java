package com.yuki.rubinco.watchpoint.ingest;

public record GameLogImportResult(
    long filesRead,
    long linesRead,
    long playerJoins,
    long playerLeaves,
    long playerListPositions,
    long entityKills,
    long levelXpSummaries,
    long sleeperSpawns,
    long sleeperRestores,
    long serverMetrics,
    long skippedServerMetrics,
    long worldEvents,
    long vehicleEvents,
    long malformedLines
) {
}
