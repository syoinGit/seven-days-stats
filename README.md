# Rubinco

A shared game portal with Watchpoint for 7 Days to Die statistics and Luminite for Terraria.

The first game-portal MVP includes the existing Watchpoint site,
`portal.rubinco.link`, and the Terraria shell `luminite.rubinco.link` run from the same Spring Boot
artifact and share Cognito SSO and one PostgreSQL timeline. The EC2-hosted sites are intentionally
unavailable while the game EC2 is stopped. Architecture, migration, cost, and rollout details are in
[docs/PORTAL_MVP.md](docs/PORTAL_MVP.md).

The application is named Rubinco (`com.yuki.rubinco.RubincoApplication`) and builds
`target/rubinco.jar`; deployment scripts install it as `app/app.jar`. Watchpoint and Luminite
remain the names of the individual game sites. Local configuration inherits the application
name from `application.yml`.

WATCHPOINT turns collected server logs into a mechanical, wasteland-themed activity feed designed to make the game more fun. It imports `players.xml`, Docker logs, and Telnet `lp` output, then connects player activity, combat, vehicles, locations, blood moon alerts, and server telemetry.

## Stack

- Java 21
- Spring Boot 4
- Thymeleaf
- PostgreSQL
- Flyway
- Maven Wrapper

## Main Features

- Imports 7 Days to Die save data such as `players.xml`, world POIs, game entities, and Japanese localization data.
- Streams or imports Docker logs for JOIN, LEAVE, KILL, SLEEPER, XP, and server metric events.
- Polls Telnet `lp` output once per minute to refresh the authoritative online player state.
- Links vehicles to players from logged owner IDs or an unambiguous nearby fresh player position.
- Tracks travel distance for each vehicle and player, including verified vehicle distance attributed to its driver.
- Attributes vehicle movement only when a fresh, verified driver position matches the vehicle; ambiguous movement is excluded from player totals.
- Classifies player movement as on-foot, verified vehicle, or unknown instead of guessing from vehicle ownership alone.
- Condenses routine activity to one game event per five-minute window while always retaining login, logout, and horde alerts; up to 80 routine events are available through lightweight infinite reveal, and blood moon alerts live in the sidebar.
- Refreshes the live timeline once per minute while idle; pauses automatic refresh for unsent posts, open reaction menus, and readers browsing older records. Failed page loads can be retried without losing the displayed history.
- Provides dedicated player, server telemetry, combat, and vehicle pages.
- Builds adventure rankings from kills, travel distance, vehicle distance, and completed login sessions.
- Aggregates seven days of activity for charts and future AI-generated daily adventure journals.
- Builds an administrator-only, provider-neutral WATCHPOINT analysis payload from the latest 30 minutes and the preceding comparison window, sent to AWS Bedrock.
- Shows explored and unexplored world POIs inferred from player positions within 80 metres.
- Ranks defeated enemy types, charts daily kills, and summarizes character XP growth.
- Aggregates verified vehicle distance by driver and vehicle type while excluding unlinked vehicle noise.
- Identifies players by stable external IDs, preferring EOS ID, then Steam ID.
- Shows online players' activity statuses (活動中, ごはん中, AFK, 外出, 就寝中, ソロ探索中) from the web or in-game commands such as `!飯`, `!afk`, and `!ソロ`.
- Infers exploration when an online player remains within 20 metres for at least three minutes; otherwise the automatic status remains moving/online, while manual statuses take precedence.
- Sends status changes back to the game through the optional Telnet command client; offline players remain read-only and show their last known location.
- Mixes player posts and Bedrock-generated WATCHPOINT observations into the adventure timeline.
- The AI murmurs once an hour (`AI_MURMUR_INTERVAL`) across every game, about one server picked at random among those online with someone connected (7DTD from its log, Core Keeper from its chest changes and connections; Terraria is not included). Nobody connected anywhere: no murmur. A 7DTD murmur is also broadcast to the in-game chat. The survivor/server analysis and daily summary post types are no longer scheduled.
- Uses one AI voice on every site: WATCHPOINT, an AI that watches over the game servers and is quietly curious about the people playing on them (calm, polite, sparing with feeling; V65). Its personality lives in the AI agent master (the retired Survivor Karen / Mark NPCs are disabled and their posts hidden). AI posts are generated only while that game's server is online. WATCHPOINT maintains a bounded rolling observation memory (up to 8 items / 1,000 characters) and deterministic alertness, curiosity, empathy, tension, and hope gauges; every successful state change is auditable in memory history.
- Uses compact aggregate-only payloads and skips empty observation windows (the turn passes to another connected server).
- Keeps all game, player, and AI history in the database, renders only the latest 12 timeline items initially, and progressively reveals older items while scrolling.
- Replaces the single like action with lightweight game-oriented emoji reactions.
- Uses a public landing page, then requires authentication for the dashboard and all data pages.
- Supports a read-only `VIEWER` guest login alongside `PLAYER` and `ADMIN` accounts; guest responses preserve display names and mask external platform IDs, remove player-dossier links, and never expose mutation controls.
- Lets administrators issue login accounts, link each account to one game player, and reset non-guest passwords; passwords are stored as BCrypt hashes.
- Displays event timestamps in JST (`Asia/Tokyo`) as `yyyy-MM-dd HH:mm:ss`.

## Repository Layout

```text
.
├── app/                         # Built production jar location
├── scripts/
│   ├── build-app.sh             # Runs tests/build and writes app/app.jar
│   └── run-app.sh               # Local jar runner
├── src/main/java/com/yuki/rubinco/
│   ├── account/                # Portal users, profiles and security
│   ├── portal/                 # Game catalog and shared portal
│   ├── timeline/               # Common posts and reactions
│   ├── watchpoint/             # 7DTD ingestion, AI and dashboard
│   ├── luminite/               # Terraria site
│   ├── infrastructure/         # Persistence compatibility
│   └── shared/                 # Cross-feature helpers and web advice
├── src/main/resources/
│   ├── db/migration/            # Flyway migrations
│   ├── static/                  # CSS/images
│   └── templates/               # Thymeleaf templates
├── src/test/java/               # Tests
├── 7dtd/                        # Local-only 7DTD test data (Git-ignored)
├── compose.example.yml          # PostgreSQL Compose template
├── .env.example
└── pom.xml
```

Javaパッケージの責務と依存方向は [ARCHITECTURE.md](ARCHITECTURE.md) にまとめています。

Runtime data under `7dtd/data`, `7dtd/game`, `7dtd/log`, `.env`, and built jars are intentionally ignored by Git.

## Configuration

For local development, copy [.env.local.example](.env.local.example) to `.env` and set the
database and bootstrap administrator passwords. For a new EC2 deployment, use
[.env.example](.env.example): only the database/Telnet passwords, Cognito issuer, and three
public app-client IDs are required. Keep an existing deployment's `.env` in place.

Ports, EC2 paths, site URLs, schedules, and feature defaults live in `application*.yml`.
Production defaults to port `8082` and `/home/ec2-user/7dtd`; site URLs derive from the
single domain `rubinco.link`. See [Configuration overrides](RUNNING.md#configuration-overrides)
for optional host, path, authentication, and AI/image settings.

The 7DTD adventure diary (pages, maintenance and generation) has been removed. Its old timeline
posts are hidden by migration V64; the stored rows are kept.

For local or legacy form authentication, the fixed `admin` login and
`WATCHPOINT_BOOTSTRAP_PASSWORD` create the first administrator account on startup when the password is set.
The bootstrap password is immediately stored as a BCrypt hash; its plaintext value is only read
from the environment. After logging in with form authentication, use `/maintenance/accounts` to
issue player accounts. Cognito production does not require a bootstrap password; invite users
through Cognito.

Administrators can inspect the generated AI request at
`/maintenance/ai-analysis/payload`. It contains the WATCHPOINT system prompt, a
strict JSON response contract, aggregate changes, survivor activity, localized
POIs, and bounded evidence events. The payload deliberately excludes platform
IDs, raw log lines, source paths, and exact coordinates. When `WATCHPOINT_AI_ENABLED`
is enabled, the application invokes Claude Haiku 4.5 (`jp.anthropic.claude-haiku-4-5-20251001-v1:0`; set `APP_AI_MODEL_ID` to switch, e.g. to Haiku 5.5 once the account has access) through InvokeModel,
validates `body` and `evidenceKeys`, and saves the short observation only after
validation succeeds. AWS credentials come exclusively from the SDK default
credential chain, so EC2 uses its attached IAM role.

The administrator-only `/maintenance/ai-analysis/test` page previews the exact
observation JSON and provides a CSRF-protected button for a real one-off Bedrock
generation. A successful test is saved and immediately becomes the latest AI
observation shown on the dashboard; failures are logged without saving a post.

Every Bedrock call in the app (WATCHPOINT, the AI murmur, guild residents, interactions, dungeon stories and talks) goes
through `BedrockTextClient`, which counts it in `T_AI_CALL_BUDGET` (V68) before calling and refuses it, without calling
Bedrock, once a hard limit is reached (Tokyo days and hours; failed calls count too):

| Setting | Default | Ceiling |
|---|---|---|
| `AI_BUDGET_DAILY_CALLS` | 50 calls a day | 200 |
| `AI_BUDGET_HOURLY_CALLS` | 20 calls an hour | 60 |
| `AI_BUDGET_DAILY_INPUT_TOKENS` | 250,000 | 1,000,000 |
| `AI_BUDGET_DAILY_OUTPUT_TOKENS` | 80,000 | 300,000 |
| `AI_BUDGET_MAX_TOKENS_PER_CALL` | 2,000 output tokens | 4,000 |

A value above its ceiling is held at the ceiling. Token limits use what the replies report, so a day can go past them
by at most one call. At the defaults a day's worst case is about 250k input and 82k output tokens (with Haiku 4.5 at
$1 / $5 per million, about $0.66). A typical 20:00-23:30 day uses about 40 calls' worth of the budget at most (around
40k input and 13k output tokens). Today's use is shown on `/maintenance/ai-analysis/test`. A refused guild interaction
falls back to its fixed text; other features skip that run.

The production profile enables secure session cookies. Keep `.env`, PostgreSQL, the 7DTD log/save directories, and the
reverse-proxy access logs outside public storage. The application cannot protect data after a
server, database, or proxy administrator has been compromised.

The public landing page is `/`. `/dashboard`, `/server`, `/kills`, `/vehicles`, and `/exploration`
require either a player/admin login or the read-only guest login. The guest view
is useful for a portfolio/demo, but external IDs are masked only in application HTML responses; database ports
and the underlying database must remain private. Remove any old Nginx `auth_basic` rule only
after HTTPS and the application login have been tested.

Spring Boot does not automatically load `.env`. Production uses `scripts/run-app.sh`, which exports
the file and defaults to the `production,cognito` profiles. Local development should activate the local profile
and provide secrets through the IDE or shell environment.

```bash
set -a
source .env
set +a
```

## Database

The app expects PostgreSQL and uses Flyway migrations from:

```text
src/main/resources/db/migration
```

Do not edit already-applied Flyway migrations. Add a new versioned migration instead.
The manual development reset lives in [scripts/reset-database.sql](scripts/reset-database.sql),
outside the application resources so it is not included in the production JAR.

## Test

```bash
./mvnw test
```

The timeline's browser event and request regressions use Node's built-in test runner:

```bash
node --test src/test/js/timeline.test.cjs
```

## Build

Use the project build script:

```bash
scripts/build-app.sh
```

This runs the Maven package lifecycle and places the production jar at:

```text
app/app.jar
```

## Local Run

From the project root:

```bash
set -a
source .env
set +a
java -jar app/app.jar --spring.profiles.active=local
```

The application must be started from the project root so relative paths such as `SEVEN_DAYS_ROOT=7dtd` resolve correctly.

## Production

The manual deployment can keep a Git checkout at `/home/ec2-user/seven-days-stats`; the existing
GitHub Actions route instead updates the artifact-only path under
`/home/ec2-user/rubinco/apps/watchpoint`. Live 7DTD data remains separate under
`/home/ec2-user/7dtd`. See `RUNNING.md` before changing either layout or its systemd unit.

## Notes

- Player uniqueness must not be based on name alone.
- EOS ID is the first stable identity key, Steam ID is the fallback.
- Entity ID may change after reconnects or server restarts and should not be used as the master identity.
- Existing duplicate player/history rows should be handled safely in views or through explicit reviewed SQL, not by risky automatic deletes.

### 無人の冒険者ギルド（軽量初期版）

`/guild` でログ由来の創作住人が集う酒場を公開します。毎日21時（JST）または管理者の `/guild/maintenance` 操作で最大1人/日を生成。既定は生成無効です。設定・出典の扱い・検証・今回の実装範囲は [docs/GUILD.md](docs/GUILD.md) を参照してください。
