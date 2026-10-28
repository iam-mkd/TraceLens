# TraceLens

A local-first distributed-systems reliability lab for reproducing failures,
observing their effects, and checking resilience mechanisms.

**Status: Milestone 3 implementation.** The application has a Redis cache-aside
product endpoint backed by PostgreSQL, with an opt-in local cache-stampede lab.
Optional OpenTelemetry traces can be inspected in Jaeger. This is not a production-ready
service or a performance benchmark. See [progress](docs/PROGRESS.md) for validation.

## Local quickstart

Requirements: Docker Engine with Linux containers and Docker Compose v2.20+.
Docker Desktop users must start the engine first. The container build uses Java
25; a host JDK or Maven installation is not required for this quickstart.

1. Copy `.env.example` to `.env` (`Copy-Item .env.example .env` in PowerShell).
2. Edit `.env` and set `TRACELENS_DB_PASSWORD` to a new password used only for this lab.
3. Run:

```sh
docker compose up --build --wait --wait-timeout 180
curl --fail http://127.0.0.1:8080/api/products/1
```

On Windows use `curl.exe`, or:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/products/1
```

Expected synthetic fixture:

```json
{"id":1,"name":"TraceLens Demo Product","price":19.99,"currency":"USD"}
```

The first lookup reads PostgreSQL and writes Redis with a 300-second TTL. The
next lookup uses Redis. Missing products return 404 and are never cached. Invalid
IDs return 400. Redis, cache decoding, and database failures return a sanitized
503 problem response. Redis failure does not fall back to PostgreSQL.

Useful local endpoints:

- `/actuator/health/readiness`: application, PostgreSQL, and Redis readiness.
- `/actuator/metrics/tracelens.product.sql.attempts`: product SQL executions.
- `/actuator/metrics/tracelens.cache.lookups`: cache outcomes by tag.
- `/actuator/metrics`: available metrics, including HTTP request timers.

Send `X-Correlation-ID` (1–64 letters, digits, dots, underscores, or hyphens) to
correlate a response with structured JSON logs. Invalid values are replaced with
a generated UUID. Every response includes the effective ID.

Only application port 8080 is published, on IPv4 loopback. PostgreSQL and Redis
have no host port mappings. Metrics have no authentication; keep this lab local.

Stop the stack with `docker compose down`. PostgreSQL data remains in the named
volume. Do not delete the volume to fix a startup issue without considering its
data. Changing the password in `.env` does not change a database role password
in an already initialized volume.

## Run a cache-stampede experiment

The lab adds authenticated control endpoints and deliberately delays product SQL.
Use only the included, isolated Compose services. The Docker host and its network
are the trust boundary; a local token is not a multi-user security system.

First create `.env` and set the local database password as described above. Add a
new high-entropy token to `.env` as `TRACELENS_LAB_TOKEN`. Use at least 128 bits
of randomness, encoded as 32–128 ASCII letters, digits, underscores or hyphens.
For example, this PowerShell generates a 256-bit token without displaying it:

```powershell
$tokenBytes = [byte[]]::new(32)
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($tokenBytes)
$random.Dispose()
$labToken = [BitConverter]::ToString($tokenBytes).Replace('-', '').ToLowerInvariant()
Add-Content .env "TRACELENS_LAB_TOKEN=$labToken"
Remove-Variable tokenBytes,random,labToken
New-Item -ItemType Directory -Force reports | Out-Null
```

Only add the token line once. Protect `.env`; do not paste the token into a
report or commit it. If you regenerate it, restart the lab application.

```powershell
docker compose -f compose.yaml -f compose.lab.yaml up --build --wait --wait-timeout 180
docker compose -f compose.yaml -f compose.lab.yaml --profile experiment run --rm runner
```

The runner uses 32 requests per burst, five cohorts per mode, a 100 ms synthetic
SQL delay, and writes one JSON and one Markdown report to `reports/`. To try a
smaller configuration, replace the runner command with:

```powershell
docker compose -f compose.yaml -f compose.lab.yaml --profile experiment run --rm runner experiment --concurrency 8 --repetitions 1 --delay-ms 100
```

Bounds are concurrency 1–64, repetitions 1–20 per mode, and delay 0–500 ms.
The same settings apply to every mode. Exit code 0 means the assertions passed;
2 means a valid failure cohort did not demonstrate amplification; 1 means a
failed assertion, invalid execution, or report error. A concurrency-1 run is a
useful way to exercise the inconclusive outcome.

If a runner stops unexpectedly, the session lease expires after 30 seconds. The
application continues blocking unrelated product traffic and new sessions while
previously admitted work or preparation remains active. Retry after the lease
has expired and that work has drained. Reports from an interrupted run can be
incomplete; do not compare them as valid cohorts.

Lab startup enforces the included `postgres:5432/tracelens` and `redis:6379`
targets, a 16-connection pool, 64 Tomcat threads, and the `300s` cache TTL.
These fixed settings are recorded in the report; remove conflicting environment
overrides before enabling the lab profile.

On Linux/macOS, create `reports/` before starting the runner. If the default
non-root container user cannot write the bind mount, run Compose with
`TRACELENS_RUNNER_UID=$(id -u) TRACELENS_RUNNER_GID=$(id -g)` in its environment.
The runner is a separate process using the same image; the host needs no JDK.

When finished, run `docker compose -f compose.yaml -f compose.lab.yaml down`.
The database volume is preserved. Lab controls remain disabled when starting
the ordinary Compose configuration without the lab override.

## Optional trace evidence

After configuring the lab token and password above, add the tracing override:

```powershell
docker compose -f compose.yaml -f compose.lab.yaml -f compose.trace.yaml up --build --wait --wait-timeout 240
docker compose -f compose.yaml -f compose.lab.yaml -f compose.trace.yaml --profile experiment run --rm runner
```

Open http://127.0.0.1:16686 or use the per-cohort trace links in the Markdown
report. The runner and application use the pinned OpenTelemetry Java agent with
100% sampling and OTLP over HTTP. Runner request spans propagate W3C context to
the application; application spans show cache lookups, coalescing, and origin
loads alongside automatically instrumented HTTP and JDBC operations. Redis
operations are represented by TraceLens cache spans; separate Lettuce command
spans were not observed with the tested agent and stack. A follower's wait span
does not contain the leader's database call, which belongs to a different request.

Reports record configured settings and observed trace context, not proof of
export or retention. Jaeger's in-memory storage is transient. Export failure
does not change experiment assertions, though instrumentation adds overhead;
do not compare latency across traced and untraced runs as a mitigation effect.
The Jaeger UI is unauthenticated and bound to loopback; traces are synthetic lab
data and must remain local. To stop this stack, use the same three `-f` options
with `down`. The original lab commands remain usable without tracing.

## Build and tests

For host development install JDK 25 and start Docker. The Maven wrapper downloads
Maven 3.9.12 on first use and requires internet access for dependencies.

```sh
chmod +x mvnw    # if the checkout did not preserve executable permissions
./mvnw test       # unit tests only
./mvnw verify     # unit tests + real HTTP/Redis/PostgreSQL integration tests
```

Windows equivalents:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

Integration tests start isolated containers with generated test configuration;
they do not use `.env` or the Compose database. Outage tests briefly pause only
their own containers and unpause them in `finally`. Missing Docker fails the
integration suite; it is not treated as a pass. First downloads can take several
minutes. Docker/CI builds use Java 25; compilation also works on a newer JDK with
`--release 25`, but that does not substitute for testing on Java 25.

`docker compose` reads `.env`; Maven/Spring Boot does not. To run the application
directly, explicitly provide `TRACELENS_DB_USERNAME`, `TRACELENS_DB_PASSWORD`,
`TRACELENS_DB_URL`, and Redis connection variables pointing to your isolated lab.
No additional host-development database ports are published by default.

## Configuration and measurement

| Setting | Default |
| --- | --- |
| `TRACELENS_DB_USERNAME`, `TRACELENS_DB_PASSWORD` | Required by the application |
| `TRACELENS_DB_URL` | Local PostgreSQL URL; overridden by Compose |
| `TRACELENS_REDIS_HOST`, `TRACELENS_REDIS_PORT` | `localhost`, `6379` |
| `TRACELENS_CACHE_TTL` | `300s`; validated between 1 ms and 1 day |
| Database pool | Fixed at 16 connections; acquisition timeout 1 s |
| Product SQL timeout | 2 s; PostgreSQL socket timeout 3 s; cancel handshake timeout 1 s |
| Redis connect / command timeout | 500 ms / 1 s |
| Tomcat request threads | Maximum 64 |

Custom database URLs must retain suitable driver connect/socket/cancellation timeouts. The
product statement independently sets a 2-second query timeout. Timeouts bound
individual dependency operations; this milestone does not enforce a total
end-to-end HTTP deadline.

Foundation metrics are cumulative for the application process, not run-local
experiment evidence; lab reports use separately captured cohort counters.
SQL attempts count only product SELECT execution attempts,
after connection acquisition. Connection acquisition and SQL duration are timed
separately. Active SQL measures outstanding client operations, not database CPU
activity. There are no measured performance improvements to report.

See [architecture](docs/ARCHITECTURE.md), [decisions](docs/DECISIONS.md),
[experiment specification](docs/experiments/cache-stampede.md),
[roadmap](docs/ROADMAP.md), and [validation status](docs/PROGRESS.md).

Contributions are welcome under [Apache-2.0](LICENSE). Read
[CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and
[SECURITY.md](SECURITY.md) before contributing.
