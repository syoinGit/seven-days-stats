# Project structure

Java sources live under `com.yuki.rubinco`, grouped by feature. Each feature owns its
controllers, use cases, configuration, and persistence adapters. Tests follow the same
package structure. `RubincoApplication` stays at the root for component and JPA scanning.

| Package | Responsibility |
| --- | --- |
| `account` | Shared portal users, profiles, manual player linking, authentication and authorization |
| `account.web` | Separate authentication, profile, and account-maintenance controllers |
| `portal` | Game catalog, host routing, server state/start requests and cross-game timeline queries |
| `timeline` | Shared posts, reactions, player social actions and deterministic timeline copy |
| `watchpoint.ingest` | 7DTD log parsing/import, Telnet polling, Docker streams and their scheduled runners |
| `watchpoint.ai` | AI agents, publishing, prompts, Bedrock adapters and agent persistence |
| `watchpoint.player` | Player lookup and status rules |
| `watchpoint.persistence` | Imported 7DTD entities and repositories shared by ingestion and read models |
| `watchpoint.readmodel` | Dashboard/diary queries, observations and display formatting |
| `watchpoint.web` | Watchpoint controllers |
| `luminite.web` | Terraria page controller using shared portal services |
| `infrastructure.persistence` | Flyway compatibility callback |
| `shared` | Small stateless helpers such as hashing, timestamps and player identity |
| `shared.web` | Cross-site authentication/profile model advice |

## Dependency direction

Controllers and scheduled runners call use cases; use cases use repositories and entities.
Game-specific code can consume shared account, portal and timeline capabilities. Luminite does
not depend on Watchpoint controllers. AI observations and diary queries live in `readmodel`,
so background publishing does not depend on the web controller package.

`watchpoint.ingest.log.parser` and the helpers directly in `shared` are framework-independent.
`shared.web` is a separate web adapter and may reference the controllers it advises.
These are package boundaries within one Spring Boot application, not independent services;
feature packages may still collaborate through their use cases and persistence models.

## Persistence consolidation

The active social-post model is `T_TIMELINE_POST` with `T_TIMELINE_POST_REACTION`, shared by
the portal and game sites. `TimelinePostService` owns its queries and mutations; the obsolete social-service facade has been removed. The unused
`T_PlayerPost` / `T_PlayerPostLike` entities and repositories have been removed, leaving
36 Spring Data repositories.

The physical `T_PLAYER_POST` and `T_PLAYER_POST_LIKE` tables remain as legacy data. V23 already
migrated their contents into the common timeline. Do not replay that backfill: it could restore
posts that users subsequently deleted from the timeline. Existing Flyway migrations are kept
unchanged; dropping these legacy tables requires a separate data-retention decision.

Other similar-looking tables retain distinct responsibilities:

- `M_WEB_ACCOUNT` owns portal authentication/profile data; `M_PLAYER` owns imported 7DTD
  identity. Their optional link allows either identity to exist independently.
- `T_AI_COMMENT` owns AI diary content and editing history, while timeline rows are its
  published representation. They are not interchangeable records.
- Current player/server state and historical observations support different queries and
  retention needs. Combining them would complicate imports and historical reconstruction.

This refactor changes Java ownership without renaming database tables or public routes.

## Refactoring boundary

The dashboard composes read models; `ServerOverviewService` owns server telemetry queries,
health classification and chart points. Player, movement and exploration queries remain together
where their inference rules share data. `DashboardController`, `DiaryController` and
`PlayerInteractionController` own separate HTTP use cases, with `TimelinePage` mapping the
persisted feed for the full page and pagination fragments. The obsolete in-memory timeline merger
has been removed; the reporting event projection remains in `DashboardViewService`.

`BedrockTextClient` owns Converse transport and response text assembly. The three domain clients
own their prompts, output limits and validation; `JsonObjectResponse` handles quoted braces and
nested objects consistently for all of them. Local and Cognito browser chains share
`WebSecurityHeaders` while retaining their separate authentication and API policies.

Player-list imports accept only complete responses whose declared count matches distinct player
entities. Replayed snapshots older than the stored current state still contribute position history,
but do not replace live state. Player first/last-seen bounds remain chronological, and historical
events cannot borrow a position observed after the event. The Telnet command client enforces the
enable flag for broadcasts as well as scheduled polling.

## Game portal extension

The page hierarchy is **Portal → game sites (Watchpoint / Luminite)**. The portal is
home for the shared account and cross-game activity feed; individual sites show game-specific
details. Local `/` opens `/portal`, while dedicated Watchpoint and Luminite host roots retain
their game entry points. `/portal` is a public home page with an icon sidebar for game sites.
`/login` redirects to the portal with `?login=true`, which opens its login dialog. Failed
logins reopen that dialog with an error; local authentication always returns to `/portal`.
Watchpoint headers link back to the portal, while their internal navigation stays game-specific.

With Cognito, unauthenticated game-page requests redirect to the canonical portal login.
After portal authentication, game links initiate that site's OAuth client using Cognito SSO.
This establishes each host's own session without sharing a session cookie across subdomains.
OAuth success selects `/portal`, `/dashboard`, or `/luminite` from the authenticated client
registration, and discards stale saved requests to avoid bouncing back to the login page.

The portal reads a single paginated timeline across configured game IDs from
`T_TIMELINE_POST`, restricted to visible posts and ordered by publication time then ID,
both descending. Entries identify and link to their game detail site. The signed-in portal contains only this feed, with 20 records per page. Guest accounts use
the same portal view through the existing HTML privacy filter; anonymous users see the public
introduction and open login from the header. The sidebar combines labeled game links with
server status read in one batch from the shared state table; it does not probe live servers.
Both game sites render the same header fragment, preserving each site's name and accent.
The shared feed does not duplicate stored posts or change the game-scoped APIs.

The portal MVP remains an adapter inside the existing deployable artifact. `SiteHostRoutingFilter`
maps the Portal and Luminite host roots, while their controllers do not depend on Watchpoint query
controllers. `GamePortalService` reads shared current state, `GameTimelineService` owns the
game-scoped API contract, and `GameServerStartGateway` isolates the safe mock from a future EC2
adapter. All timelines remain in `T_TIMELINE_POST` and are selected by `game_id`; Watchpoint writes
explicitly use `7dtd`, and Luminite reads `terraria` directly through `GameTimelineService` rather
than depending on Watchpoint's dashboard controller or presentation model.

Event edits and start requests lock the same game-state row until their transaction commits,
preventing concurrent saves from overwriting each other's status or progress. Removing an event's
last progress/stage value reprojects the latest remaining value, or falls back to the catalog.
The current start gateway is an immediate mock; a future external provider should perform slow
network operations outside this database transaction.

The `cognito` profile replaces local form authentication with Spring Security OIDC for web routes
and a stateless JWT resource-server chain for `/api/**`. The `local` profile deliberately retains
the existing account flow for development. See [docs/PORTAL_MVP.md](docs/PORTAL_MVP.md) for the
deployment and migration boundaries.

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

`WebSecurityConfig` and the profile-specific `CognitoSecurityConfig` define authorization; the path lists both use (public guild pages, admin maintenance, player posts, static files) live in `AccessPaths`.
The local/form-login API requires a `PLAYER` or `ADMIN` account because external-ID masking
applies to HTML responses, not raw JSON. `GuestPrivacyFilter` runs only after
Spring Security has authenticated a `VIEWER` request, and delegates response rewriting to
`GuestPrivacyService`. The service is a presentation safety net: it preserves display names,
masks external platform identifiers, and removes player-dossier links. The
controller still blocks guest dossier access and all guest mutations; templates must not be used
as the only authorization mechanism.

## Portal user profiles

`M_WEB_ACCOUNT` is the shared Rubinco user identity (the role intended for an `M_user` table),
separate from imported `M_PLAYER` game identities. V39 adds `display_name` and `avatar_key`
without recreating accounts or changing existing player links. A user can join the portal without
linking a game player. The existing optional, unique `player_id` remains the 7DTD link; only
administrators can assign or remove it. Display-name edits never rename imported game players.

`PortalProfileService` resolves one icon for the shared account and its linked player. Unlinked
players receive a stable default; guest views use a neutral guest icon. Icons are bundled SVG
presets selected from an allowlist, with no upload storage or external image hosts required.
`/portal/profile` updates only the current authenticated non-guest account and retains CSRF
protection. Templates receive profile-only lookup maps, not account entities or credentials.

Future Terraria account linking should use a dedicated relation keyed by shared user, game, and
stable game identity after ownership-verification rules are defined. Player names must not be
used for automatic linking. Do not repurpose the existing 7DTD `player_id` for another game.

Manual account linking is owned by the portal maintenance route
`/portal/maintenance/accounts`. Both security chains restrict the entire
`/portal/maintenance/**` namespace to administrators. Legacy account-maintenance URLs remain
compatible and return to the canonical portal page. Account rows carry the current player ID,
so editing a link does not require matching a user and player in separate forms. The overview
loads accounts and players in batches instead of querying a player for each account.


## Persistence and import consistency

V40 adds nullable `T_TIMELINE_POST.author_account_id`, referencing the original portal account.
It is independent of the editable game-player link. Historical authorship is recovered from the
legacy post's account ID or the account ID recorded in the post's source hash; unknown authors
stay unknown. Deleting an account clears this reference without deleting the post. Player-message
delete authorization and the UI's ownership flag use this reference, including after unlinking.

Feed reactions are loaded once per page. Reaction toggles and post deletion lock the same post row
until commit so concurrent requests cannot create duplicate reactions or race a deletion. V41
adds indexes for game/visibility-ordered feeds, game/type cooldown checks and latest player snapshots.

`ImportTransaction` commits each data import, log file, Telnet response or Docker event batch
atomically. Running guards and stream buffers advance only after commit. Streaming player context
is copied before processing and published only after commit; database exceptions are propagated,
not counted as malformed input. `DataImportRunService` writes start/completion/failure audit rows
in independent transactions, so a rolled-back import leaves a diagnosable failure record.
