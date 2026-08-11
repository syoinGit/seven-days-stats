# WATCHPOINT

7 Days to Die dedicated server logs and save data observation dashboard.

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
- Condenses routine activity to one game event per five-minute window while always retaining login, logout, and horde alerts; up to 80 routine events are available through lightweight infinite reveal, the update button announces a new observation after five minutes, and blood moon alerts live in the sidebar.
- Provides dedicated player, server telemetry, combat, and vehicle pages.
- Builds adventure rankings from kills, travel distance, vehicle distance, and completed login sessions.
- Aggregates seven days of activity for charts and future AI-generated daily adventure journals.
- Builds an administrator-only, provider-neutral WATCHPOINT analysis payload from the latest 30 minutes and the preceding comparison window, ready for an AWS Bedrock Converse adapter.
- Shows explored and unexplored world POIs inferred from player positions within 80 metres.
- Ranks defeated enemy types, charts daily kills, and summarizes character XP growth.
- Aggregates verified vehicle distance by driver and vehicle type while excluding unlinked vehicle noise.
- Identifies players by stable external IDs, preferring EOS ID, then Steam ID.
- Shows online players' activity statuses (活動中, ごはん中, AFK, 外出, 就寝中, ソロ探索中) from the web or in-game commands such as `!飯`, `!afk`, and `!ソロ`.
- Infers exploration when an online player remains within 20 metres for at least three minutes; otherwise the automatic status remains moving/online, while manual statuses take precedence.
- Sends status changes back to the game through the optional Telnet command client; offline players remain read-only and show their last known location.
- Mixes player posts and Bedrock-generated WATCHPOINT observations into the adventure timeline; daily journals live in the right sidebar so the central feed stays focused.
- Publishes one fictional `SURVIVOR_KAREN` lifestyle post per JST day from local weighted templates, independently of real players and game logs; occasional Nova Canvas images are uploaded to S3 and safely fall back to text-only posts.
- Publishes typed WATCHPOINT posts (`NORMAL`, survivor/server analysis, and a short daily summary); only `NORMAL` is broadcast to the in-game chat.
- Uses compact aggregate-only payloads for analysis posts, caps paid timeline generation at 10 posts per JST day by default, and skips empty observation windows.
- Keeps all game, player, and AI history in the database, renders only the latest 12 timeline items initially, and progressively reveals older items while scrolling.
- Replaces the single like action with lightweight game-oriented emoji reactions.
- Uses a public landing page, then requires authentication for the dashboard and all data pages.
- Supports a read-only `VIEWER` guest login alongside `PLAYER` and `ADMIN` accounts; guest responses anonymize player names and external platform IDs, remove player-dossier links, and never expose mutation controls.
- Lets administrators issue login accounts, link each account to one game player, and reset non-guest passwords; passwords are stored as BCrypt hashes.
- Displays event timestamps in JST (`Asia/Tokyo`) as `yyyy-MM-dd HH:mm:ss`.

## Repository Layout

```text
.
├── app/                         # Built production jar location
├── scripts/
│   ├── build-app.sh             # Runs tests/build and writes app/app.jar
│   └── run-app.sh               # Local jar runner
├── src/main/java/               # Spring Boot application
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

Copy the example environment file and fill in local or production values.

```bash
cp .env.example .env
```

Production `.env` contains only secrets, host-specific paths, and operational feature flags.
Intervals, timeouts, schedules, AWS regions, model IDs, and resource paths live in
`application.yml`.

```bash
POSTGRES_PASSWORD=
SEVEN_DAYS_ROOT=/home/ec2-user/7dtd
SEVEN_DAYS_CONFIG_DIRECTORY=/home/ec2-user/7dtd/data
SEVEN_DAYS_TELNET_ENABLED=true
SEVEN_DAYS_TELNET_PASSWORD=
WATCHPOINT_BOOTSTRAP_PASSWORD=replace-with-a-long-random-password
WATCHPOINT_AI_ENABLED=false
SURVIVOR_KAREN_IMAGE_ENABLED=false
WATCHPOINT_IMAGE_BUCKET=
WATCHPOINT_IMAGE_PUBLIC_BASE_URL=
```

Diary maintenance is protected by the application `ADMIN` role. Administrators can edit diaries
manually even when AI is disabled. The Bedrock generation/update button is available only when
`WATCHPOINT_AI_ENABLED=true`; diaries are never generated by a background schedule.

The fixed `admin` login and `WATCHPOINT_BOOTSTRAP_PASSWORD` create the first administrator
account on startup when the password is set. The password is immediately
stored as a BCrypt hash; the plaintext value is only read from the environment.
After logging in, use `/maintenance/accounts` to issue player accounts.

Administrators can inspect the generated AI request at
`/maintenance/ai-analysis/payload`. It contains the WATCHPOINT system prompt, a
strict JSON response contract, aggregate changes, survivor activity, localized
POIs, and bounded evidence events. The payload deliberately excludes platform
IDs, raw log lines, source paths, and exact coordinates. When `WATCHPOINT_AI_ENABLED`
is enabled, the application invokes Claude Haiku 4.5 through the Converse API,
validates `body` and `evidenceKeys`, and saves the short observation only after
validation succeeds. AWS credentials come exclusively from the SDK default
credential chain, so EC2 uses its attached IAM role.

Karen's text posts do not invoke Bedrock. When Karen image generation is enabled,
the EC2 role needs `bedrock:InvokeModel` for the configured Nova Canvas model and
`s3:PutObject` for the configured prefix. `WATCHPOINT_IMAGE_PUBLIC_BASE_URL` should
point to the bucket's public delivery URL or a CloudFront distribution; if it is
empty, the application stores the standard regional S3 object URL. Keep
`SURVIVOR_KAREN_IMAGE_ENABLED=false` until the bucket delivery policy and model
access are ready. An image failure is logged and the day's text post is still saved.

The administrator-only `/maintenance/ai-analysis/test` page previews the exact
observation JSON and provides a CSRF-protected button for a real one-off Bedrock
generation. A successful test is saved and immediately becomes the latest AI
observation shown on the dashboard; failures are logged without saving a post.

The production profile enables secure session cookies. Keep `.env`, PostgreSQL, the 7DTD log/save directories, and the
reverse-proxy access logs outside public storage. The application cannot protect data after a
server, database, or proxy administrator has been compromised.

The public landing page is `/`. `/dashboard`, `/server`, `/kills`, `/vehicles`, `/exploration`,
and `/diaries` require either a player/admin login or the read-only guest login. The guest view
is useful for a portfolio/demo, but only application responses are anonymized; database ports
and the underlying database must remain private. Remove any old Nginx `auth_basic` rule only
after HTTPS and the application login have been tested.

Spring Boot does not automatically load `.env`. Production uses `scripts/run-app.sh`, which exports
the file and activates the production profile. Local development should activate the local profile
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

## Test

```bash
./mvnw test
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

EC2 keeps the Git repository at `/home/ec2-user/seven-days-stats` and reads the
7DTD server data from the separate `/home/ec2-user/7dtd` tree. See `RUNNING.md`
for initial setup, deployment, and systemd commands.

## Notes

- Player uniqueness must not be based on name alone.
- EOS ID is the first stable identity key, Steam ID is the fallback.
- Entity ID may change after reconnects or server restarts and should not be used as the master identity.
- Existing duplicate player/history rows should be handled safely in views or through explicit reviewed SQL, not by risky automatic deletes.
