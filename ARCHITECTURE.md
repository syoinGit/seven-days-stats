# Project structure

The application is organized by responsibility. The package names are intentionally
small and stable so that log parsing, persistence, domain processing, and the web UI
can evolve independently.

| Package | Responsibility |
| --- | --- |
| `config` | Spring configuration, application properties, migration compatibility hooks, and the guest privacy boundary |
| `batch` | Scheduled or startup runners that trigger imports and polling |
| `entity` | JPA persistence models (`M_` master tables and `T_` transaction/state tables) |
| `repository` | Spring Data repositories; database access stays behind this boundary where practical |
| `log.dto` | Immutable values produced by log parsing |
| `log.parser` | Pure, side-effect-free parsers for individual 7DTD log message families |
| `service` | Import orchestration, game-domain rules, telnet integration, and account/social use cases |
| `web` | Controllers, dashboard/diary view queries, presentation formatting, and Thymeleaf-facing models |
| `util` | Stateless cross-layer helpers that do not depend on Spring, JPA, or the web layer |

## Dependency direction

The normal direction is `web`/`batch` → `service` → `repository`/`entity`.
`log.parser` and `util` remain framework-independent. Presentation-only helpers may
be used by web code, while generic helpers such as timestamp formatting and player
identity normalization live in `util` so that import and social services do not depend
on the web package.

## Refactoring boundary

The import and dashboard services contain the SQL needed to build the current read
models and are covered by integration tests. They are kept as cohesive application
services for now; future extractions should move one query family at a time behind a
small read-model component rather than splitting methods mechanically.

## Timeline copy catalog

`timeline-copy.xml` contains original, reusable scene fragments for the deterministic
timeline generator. `TimelineCopyCatalog` validates and loads the catalog at startup;
the generator combines the fragments with the immutable event facts. Add or adjust
copy in the XML, not by collecting or reproducing dialogue from films, games, or sites.

## Historical survivor trail reports

`SurvivorMarkCandidateService` reads only imported transaction rows in a closed, historical
calendar window (default: 2–5 days old). It clusters kills, sleeper spawns, and recorded
positions into 100m exploration candidates, resolves the nearest imported POI, and excludes a
location recently used by a Mark post. `SurvivorMarkPublishingService` sends only those compact,
fact-bounded fields to Bedrock once per day and posts the resulting short report through the
existing timeline read model. It never uses live player state or current-day rows, and it never
generates an image.

## Web privacy boundary

`WebSecurityConfig` is the authorization source of truth. `GuestPrivacyFilter` runs only after
Spring Security has authenticated a `VIEWER` request, and delegates response rewriting to
`GuestPrivacyService`. The service is a presentation safety net: it maps master player names to
stable aliases, masks external platform identifiers, and removes player-dossier links. The
controller still blocks guest dossier access and all guest mutations; templates must not be used
as the only authorization mechanism.
