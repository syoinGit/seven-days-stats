# Running and deployment

## Environment

Copy `.env.example` to `.env` and set real values.

```bash
cp .env.example .env
```

`.env.example` is the tracked EC2 template. `.env` contains host-specific values
and secrets and must never be committed.

For local development, use the local profile. It defaults to file logs, local `7dtd`, insecure
cookies, and disables Telnet and AI. Supply only the database password through the IDE or shell:

```dotenv
SPRING_PROFILES_ACTIVE=local
POSTGRES_PASSWORD=your-local-password
```

For EC2, keep the small set of production secrets, paths, and flags from `.env.example`.
Timeouts, intervals, schedules, AWS regions, and model IDs are versioned in `application.yml`.
The repository directory
and the 7DTD server directory are intentionally separate:

```text
/home/ec2-user/seven-days-stats  # application Git repository
/home/ec2-user/7dtd              # live 7DTD server data
```

Copy the Compose template only for a new environment. Do not replace an existing
EC2 `compose.yml` until its PostgreSQL volume mapping has been compared.

```bash
cp compose.example.yml compose.yml
```

The Spring Boot app does not automatically load `.env`, so export it before starting the app:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

For EC2/systemd, set the same values through an `EnvironmentFile` or service environment.

### Bedrock observations

The integration is disabled by default. On an EC2 instance with an IAM role that can call
`bedrock:InvokeModel`, enable it without adding static AWS credentials:

```dotenv
WATCHPOINT_AI_ENABLED=true
```

Region and model IDs are fixed in `application.yml`. The AWS SDK uses `DefaultCredentialsProvider`, which obtains temporary credentials from the
attached EC2 IAM role. Do not add access keys, secret keys, Anthropic keys, or Bedrock API keys
to `.env`. An administrator can test one generation with `POST /maintenance/ai-analysis/publish`.

## EC2 layout

The production directory is expected to be:

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

The deployment script detects both `seven-days-stats.service` and the legacy
`sevendays-states.service`. To select a unit explicitly, run:

```bash
SERVICE_NAME=sevendays-states.service ./scripts/deploy-app.sh
```

Inspect production logs with:

```bash
sudo journalctl -u seven-days-stats.service -n 100 --no-pager
```
