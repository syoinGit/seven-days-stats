# Running and deployment

## Environment

Rubinco builds `target/rubinco.jar` and uses `com.yuki.rubinco.RubincoApplication` as its
entry point. Update an existing IDE run configuration to this class after reloading Maven.
`application-local.yml` inherits the shared application name `rubinco`.

Renaming the application does not migrate existing infrastructure: the PostgreSQL database
`seven_days_stats`, Compose container/volume, source-checkout directory, S3 object path, and
active `rubinco-watchpoint.service` are retained. For a new systemd installation, use
`infra/systemd/rubinco.service.example`; adapt its checkout paths to the actual directory.
Keep the existing unit on a running host rather than enabling a second service for the same app.

For local development, copy [.env.local.example](.env.local.example) and set its database
and bootstrap administrator passwords. The template selects the `local` profile, which defaults
to file logs and local `7dtd`, allows HTTP cookies, and disables Telnet and AI.

```bash
cp .env.local.example .env
```

For a new EC2 deployment, copy [.env.example](.env.example) to `.env` and fill its six values:
the database and Telnet passwords, Cognito issuer, and three distinct public app-client IDs.
No app-client secret or bootstrap administrator password is required for Cognito.
Keep an existing deployment's `.env` and its configured location; never commit it.

`scripts/run-app.sh` and the systemd example default to `production,cognito`.
Invalid portal or game-catalog configuration fails application startup.
Ports, paths, URLs, timeouts, schedules, AWS regions, model IDs, and feature defaults are versioned
in `application*.yml`; optional changes are documented below.

For a source-checkout deployment, the repository directory and the 7DTD server directory are
intentionally separate:

```text
/home/ec2-user/seven-days-stats  # application Git repository
/home/ec2-user/7dtd              # live 7DTD server data
```

Copy the Compose template only for a new environment. Do not replace an existing
EC2 `compose.yml` until its PostgreSQL volume mapping has been compared.

```bash
cp compose.example.yml compose.yml
```

Start the database service as well as Docker itself:

```bash
docker compose up -d --wait postgres
docker compose ps
```

For an existing `seven-days-postgres` container, keep its volume and original Compose project.
Do not create a replacement empty database to fix a port conflict. If another project's database
uses port `5432`, choose `POSTGRES_PORT=5433` in the local `.env` and use that value in the
existing Compose port mapping. The application and the new Compose template both read this
variable. Port changes require recreating the container with its **same existing data volume**;
a simple restart does not change its configured port mapping.

When running from IntelliJ, select the `local` profile and set the environment variables from
`.env` in the run configuration. A shell's exported variables are not automatically added to an
already-running IDE. `Connection refused` indicates that the selected host/port is not accepting
connections; check `docker ps` and the published port before changing the database password.

The Spring Boot app does not automatically load `.env`, so export it before starting the app:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

For EC2/systemd, set the same values through an `EnvironmentFile` or service environment.

## Game portal hosts and SSO

The production reverse proxy sends these hosts to the same Spring process while preserving `Host`:

- `portal.rubinco.link`
- `watchpoint.rubinco.link`
- `luminite.rubinco.link`
- `octarine.rubinco.link` (Core Keeper; see [docs/OCTARINE.md](docs/OCTARINE.md))
- `api.rubinco.link`

Use [infra/nginx/game-portal.conf.example](infra/nginx/game-portal.conf.example) as a merge reference,
not as a replacement for the existing Watchpoint proxy. Cognito configuration is in
[infra/cognito/template.yaml](infra/cognito/template.yaml). Its `auth.rubinco.link` certificate must
be created in `us-east-1`, even when the User Pool is in another region.

Before the first production start, back up PostgreSQL and compare the current Flyway history with
the pending migrations (including V40/V41). Full preflight, invitation, DNS, and smoke-test steps are in
[docs/PORTAL_MVP.md](docs/PORTAL_MVP.md).

## Configuration overrides

The production defaults need no additional `.env` entries. Existing environment overrides remain
supported; add only values that differ for your deployment.

| Setting | Default and purpose |
| --- | --- |
| `DOMAIN` | `rubinco.link`; derives Portal, Watchpoint, Luminite, Octarine, auth, and API URLs. |
| `PORTAL_SITE_URL`, `WATCHPOINT_SITE_URL`, `LUMINITE_SITE_URL`, `OCTARINE_SITE_URL`, `AUTH_SITE_URL`, `API_BASE_URL` | Override individual URLs when hosts do not follow the domain convention. CORS origins, game links, and OAuth callbacks follow the configured site URLs. |
| `TERRARIA_SITE_NAME` | Optional display-name override for Luminite. |
| `COREKEEPER_SITE_NAME` | Optional display-name override for Octarine. |
| `COGNITO_OCTARINE_CLIENT_ID` | Octarine's Cognito app client. While unset, the app starts and Octarine reuses the portal client, so Octarine login fails at Cognito until the client exists. |
| `COREKEEPER_WORLD_FILE` | Production: `/home/ec2-user/rubinco/servers/corekeeper/server-data/worlds/0.world.gzip`; read-only. |
| `COREKEEPER_IMPORT_ENABLED` | Production: `true`; local: `false`. Manual re-fetch from the maintenance screen works either way. |
| `COREKEEPER_IMPORT_INTERVAL` | `10m` (minimum `1m`). |
| `COREKEEPER_NAMES_DIR` | Production: `/etc/rubinco/corekeeper-names`. Generated object name table, kept out of the repository; without it names show as ids. |
| `COREKEEPER_LOG_STREAM_ENABLED` | `false` by default. Set `true` after installing the helper with `logs corekeeper` (`docs/DOCKER_PERMISSIONS.md`); reads connections and play time from the server log. |
| `TERRARIA_WORLD_FILE` | Unset by default; the server's `.wld`, read-only. Find the path before setting it ([LUMINITE.md](docs/LUMINITE.md)). |
| `TERRARIA_IMPORT_ENABLED` | `false`; enable after `TERRARIA_WORLD_FILE` is set. Manual re-fetch from the maintenance screen works either way. |
| `TERRARIA_NAMES_DIR` | Production: `/etc/rubinco/terraria-names`. Generated item/NPC name tables, kept out of the repository; without them names show as ids. |
| `TERRARIA_IMPORT_INTERVAL` | `5m` (minimum `1m`). |
| `GAME_SERVER_START_PROVIDER` | `mock`; set to `docker` on EC2 to start existing game containers from the portal. First apply [restricted Docker permissions](docs/DOCKER_PERMISSIONS.md) and [the boot policy](docs/GAME_SERVERS.md). |
| `SEVEN_DAYS_ROOT` | Production: `/home/ec2-user/7dtd`; local: `7dtd`. |
| `SEVEN_DAYS_CONFIG_DIRECTORY` | Production: `${SEVEN_DAYS_ROOT}/data`. |
| `POSTGRES_PORT` | `5432`; shared by the application JDBC URL and Compose host port. `SPRING_DATASOURCE_URL` can override the entire URL. |
| `SERVER_PORT` | Production: `8082`; local: `8080`. Local URLs follow the selected port. |
| `SEVEN_DAYS_TELNET_ENABLED` | Production: `true`; local: `false`. |
| `WATCHPOINT_AI_ENABLED` | `false`; lets 7DTD take part in the AI murmur. |
| `WORLD_AI_ENABLED` | `false`; lets Core Keeper (Octarine) take part in the AI murmur (same Bedrock model and IAM as Watchpoint AI). |
| `AI_MURMUR_INTERVAL` | `1h` (minimum `30m`); one murmur per interval across every game, from a server online with someone connected. Replaces `WORLD_AI_INTERVAL`. |

Scheduled imports for every site (7DTD data/log/telnet, Luminite, Octarine) pause while the
portal reports the game server as stopped, after one catch-up import right after it stops.
AI posts run only while the server is online, including the manual test button on
`/maintenance/ai-analysis/test`. Manual re-fetches are not paused.

Changing `DOMAIN` or individual URLs also requires matching DNS, certificates, Nginx, and Cognito
allowed callback/logout URLs. The SSM deployment health check uses port `8082`; changing
`SERVER_PORT` requires updating both Nginx and the SSM health check.

For legacy form authentication, set `SPRING_PROFILES_ACTIVE=production` when using
`scripts/run-app.sh`, and set `WATCHPOINT_BOOTSTRAP_PASSWORD` only if creating the first `admin`
account. A systemd unit that sets profiles directly must be changed separately; the run-script
override does not alter that unit.

### Bedrock

On EC2, enable `WATCHPOINT_AI_ENABLED=true` only with an IAM role that can call
`bedrock:InvokeModel`. Region and model IDs live in `application.yml`.
The AWS SDK obtains credentials through `DefaultCredentialsProvider`, including the attached
EC2 IAM role; no static AWS or Bedrock keys are needed in `.env`.
An administrator can test one generation with `POST /maintenance/ai-analysis/publish`.

## EC2 layout

The manual/source-checkout deployment layout is:

```text
/home/ec2-user/seven-days-stats
├── .env
├── app
│   └── app.jar
├── compose.yml
└── scripts
```

Live 7DTD files remain under `/home/ec2-user/7dtd` and are not part of this
repository.

The existing GitHub Actions/SSM deployment is artifact-only: it writes the JAR to
`/home/ec2-user/rubinco/apps/watchpoint/app/app.jar` and restarts
`rubinco-watchpoint.service`. Do not confuse that live path with the optional source-checkout layout
above; verify the unit's `WorkingDirectory`, `EnvironmentFile`, and JAR path with
`systemctl cat rubinco-watchpoint.service` before changing either deployment route.

Build and place the jar under `app/`:

```bash
./scripts/build-app.sh
```

Run the jar from the project root so relative paths such as `SEVEN_DAYS_ROOT=7dtd` resolve correctly:

```bash
./scripts/run-app.sh
```

Deploy the latest `main` branch, build the jar, and restart systemd:

```bash
cd /home/ec2-user/seven-days-stats
./scripts/deploy-app.sh
```

The deployment script refuses tracked and untracked source changes, keeps the previous JAR for
automatic rollback when restart or health verification fails, and detects
`rubinco.service`, `rubinco-watchpoint.service`, `seven-days-stats.service`, and the legacy
`sevendays-states.service`. To select a unit explicitly, run:

```bash
SERVICE_NAME=rubinco.service ./scripts/deploy-app.sh
```

Inspect production logs with:

```bash
sudo journalctl -u rubinco.service -n 100 --no-pager
```

The EC2-off state intentionally takes Portal, Watchpoint, Luminite, and API offline. The portal
cannot wake a stopped instance. [Game server setup](docs/GAME_SERVERS.md) configures Terraria
to start automatically and Core Keeper / Minecraft / 7dtd to start on demand from the portal.
Docker control is opt-in; the default provider remains a mock.

## Portal navigation in local development

Start `Rubinco Local`, then open `http://localhost:8080/portal` (or the configured port).
The home page is public; its login button opens a modal. Direct `/login` requests also return
to that modal, and successful local login opens the shared timeline on `/portal`.
The icon sidebar opens Watchpoint and Luminite; Watchpoint headers return to the portal.
Anonymous visitors see the public introduction with login in the header. Both members and
guests enter the shared timeline after login; guest HTML keeps the existing anonymization.
The signed-in portal keeps the timeline in the main area; the sidebar shows labeled game links
and server status. Selecting the Docker provider also adds member start controls for 7dtd,
Core Keeper, and Minecraft; Terraria remains automatic. Both game sites use the same header layout.

With Cognito, sign in on the portal first, then select a game icon. Each destination completes
its own OAuth flow through the existing Cognito SSO session. Keep all three client callback URLs
configured; a shared cookie domain is not needed.

## Shared profile icons

After restarting the app, Flyway applies V39 to add the optional profile fields. Existing
accounts, passwords, and 7DTD links are preserved. Click your avatar/name in the header to edit
your shared display name and choose an icon. Guest profiles are read-only and anonymized.
No new environment variables are required. Administrators can create portal users without a
7DTD link and later manage the link under account maintenance. Terraria identity linking is
not inferred automatically from names.

## Manual player linking

Administrators open **メンテナンス** in the portal header, at
`/portal/maintenance/accounts`. Each user row shows its current 7DTD player selection.
Choose a player and save, or select **未連携（解除）** to unlink. Duplicate player ownership is
rejected. The old `/maintenance/accounts` URL redirects to this portal page.
With Cognito, invited users appear after their first login; this screen manages game links,
while credentials remain managed by Cognito. Terraria identity linking is not yet available.


## Verification before committing

```bash
./mvnw clean verify
node --test src/test/js/*.test.cjs
```

Java tests use isolated H2 databases, exercise the migrations and never require the application's
local `.env`. To additionally exercise the V39 → latest authorship upgrade on an **empty,
disposable PostgreSQL database**, run:

```bash
./mvnw test -Dtest=TimelineAuthorshipMigrationTests \
  -Dmigration.test.url=jdbc:postgresql://127.0.0.1:55432/rubinco_refactor \
  -Dmigration.test.user=postgres \
  -Dmigration.test.password=refactor-test-only
```

The upgrade test creates and deletes fixture accounts/posts; never point it at an application DB.
