# Architecture decisions

## ADR-001 — One application and one Maven module (accepted)

Keep package boundaries around product, cache, telemetry, and configuration.
An eventual workload process will call HTTP instead of sharing server request
threads. This keeps local setup small; process separation does not eliminate
shared-host resource interference. Do not add services without evidence.

## ADR-002 — Java 25 and Boot-managed dependencies (accepted)

User approved Java 25, Spring Boot 4.1.2, JUnit Jupiter 6, and Testcontainers.
JUnit 6 replaces the original JUnit 5 preference. Boot manages Jupiter 6.0.3 and
Testcontainers 2.0.5; Maven Wrapper pins Maven 3.9.12. JDBC avoids ORM-generated
query ambiguity. Flyway provides repeatable schema history rather than implicit
startup DDL. Jackson 3 is Boot's default JSON stack.

Verified published Maven artifacts and official documentation on 2026-10-27:

- https://docs.spring.io/spring-boot/system-requirements.html
- https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html
- https://docs.spring.io/spring-boot/reference/testing/testcontainers.html

## ADR-003 — Explicit cache-aside, fail on Redis errors (accepted)

Use Redis GET and atomic SET-with-TTL explicitly, not caching annotations.
This makes lookup and load counts visible and leaves room for single-flight.
No retries, negative cache, fallback, stale serving, or background refresh.
A cache failure returns 503 even when the database is healthy; this deliberate
lab policy avoids mixing scenarios and is not a universal production policy.
Malformed cached JSON also fails explicitly rather than hiding corruption.

## ADR-004 — Pinned local container inputs (accepted)

Registry manifests verified on 2026-10-27:

- PostgreSQL `18.3-alpine`, index `sha256:54451ecb8ab38c24c3ec123f2fd501303a3a1856a5c66e98cecf2460d5e1e9d7`.
- Redis `8.6.1-alpine`, index `sha256:2afba59292f25f5d1af200496db41bea2c6c816b059f57ae74703a50a03a27d0`.
- Temurin 25 JDK Alpine, index `sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6`.
- Temurin 25 JRE Alpine, index `sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048`.

These are reproducibility pins, not a claim to use the newest patch releases or
to have completed a vulnerability audit. Update pins deliberately and rerun
integration/Compose checks. PostgreSQL 18 uses the `/var/lib/postgresql` volume
mount. Read PROGRESS.md for actual runtime validation.

## ADR-005 — Experiment protocol (accepted)

Begin with a synchronized one-shot burst and explicit key invalidation.
Use a disclosed 100 ms PostgreSQL-side delay to make overlapping misses likely.
This trades realism for a clearer causal demonstration. Natural TTL expiry and
sustained-rate load follow later. Single-flight is in-process only. See the
experiment specification for metrics, validity rules, and exact defaults.

## ADR-006 — Bound PostgreSQL cancellation separately (accepted)

The real paused-database integration test exposed that a statement timeout can
block while sending its out-of-band cancellation request, even with a socket
timeout on the main connection. Set pgJDBC `cancelSignalTimeout=1` in addition to
`connectTimeout=2`, `socketTimeout=3`, and the 2-second statement timeout. Preserve
the HTTP test deadline rather than relaxing the test. See the driver parameter
semantics: https://jdbc.postgresql.org/documentation/use/ . These are individual
operation timeouts, not a strict total request budget.

## ADR-007 — Isolated lab runner and run-local evidence (accepted)

The opt-in Compose override starts a token-protected lab profile and a one-off
runner from the same image. The runner targets only `app:8080`; the application
validates expected Compose service names and fixed resource defaults. This is a
local trust boundary, not proof against a malicious Docker host or DNS setup.
The normal profile has no lab controller. No cloud service or extra runtime
dependency is introduced.

One coordinator serializes session and cohort transitions and tracks product
requests before preparation. Preparation itself holds the session guard. Token,
session, cohort, and unique request IDs gate the measurement window; cohort IDs
cannot be reused in a session. Closing admission precedes drain; expired sessions
stay guarded while preparation or active work remains. The server records cohort data directly on
request threads and the client retains independent monotonic latency samples.
This costs small in-memory bookkeeping but avoids scrape interval and unrelated
traffic distortions. A single process and key are deliberate v0.1 constraints.

Leader/follower futures use per-key atomic insertion and removal, a second Redis
read, and a four-second follower deadline. A timed-out follower never cancels the
shared load. PostgreSQL `pg_sleep` is present only in measured lab SELECTs and
is disclosed as synthetic. Test barriers force overlapping misses without adding
production synchronization or claiming that every natural burst will overlap.

## ADR-008 — Optional agent-based trace evidence (accepted)

Use the OpenTelemetry Java agent 2.33.1 in an optional Compose image, with its
download verified against SHA-256 `e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149`.
Application code uses the Boot-managed OpenTelemetry API (1.62.0) through the
agent's global bridge, avoiding a second runtime SDK. Jaeger 2.22.0 is pinned
by image digest. Both processes sample all traces and export OTLP/HTTP with a
one-second exporter timeout; metric and log export are disabled. The agent
instruments HTTP and JDBC, while explicit spans capture cache and single-flight
semantics. The tested stack did not emit separate Lettuce command spans.

The extra instrumentation and all-trace sampling add overhead, and Jaeger's
in-memory storage is ephemeral. Exact experiment outcomes use independent
run-local counters, and reports distinguish observed context from verified trace
delivery. The collector may be unavailable without changing assertions. No
tokens are deliberately attached to spans or captured as headers.
