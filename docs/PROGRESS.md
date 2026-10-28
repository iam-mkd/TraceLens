# Progress

## 2026-10-27 — Milestone 1 foundation

Implementation includes the Java 25/Boot 4.1.2 Maven project, real cache-aside
product API, Flyway fixture, dependency timeouts, query/cache metrics, correlated
JSON logs, Compose/Dockerfile, CI, tests, and project documentation.

Validation results:

- Application and all test sources compiled with Java release target 25.
- On checksum-verified portable Temurin 25.0.5+8, `mvnw.cmd test` passed all
  12 unit/parameterized test cases (zero failures/errors/skips).
- After Docker was started, `mvnw.cmd verify` passed all 12 unit and 13 real
  HTTP/Redis/PostgreSQL integration cases (zero failures/errors/skips), and built
  the executable JAR. This ran on Temurin 25.0.5+8 with Testcontainers 2.0.5,
  PostgreSQL 18.3, and Redis 8.6.1 on Docker Desktop's Linux engine.
- The first real outage run exposed pgJDBC's separate cancellation timeout.
  Bounding `cancelSignalTimeout=1` fixed the paused-PostgreSQL case without
  relaxing the test's HTTP deadline. See ADR-006.
- `docker compose config --quiet` passed with an ephemeral local password.
- The final Linux Docker image built successfully and all three Compose services
  reached healthy state. HTTP smoke assertions passed: expected fixture DTO,
  query count `0 -> 1 -> 1` across cold/warm reads, Redis TTL 300 seconds,
  correlation response header, app UID 100 (non-root), application bound only to
  `127.0.0.1:8080`, and no published Redis/PostgreSQL ports. Local smoke evidence
  is saved in ignored `target/compose-smoke.json`.
- Dependency versions and container index digests were checked against Maven
  Central and Docker registry manifests. Maven's downloaded distribution was
  checked against its published SHA-512, then SHA-256 pinned in the wrapper.

The host's installed JDK was not changed. A portable test JDK lives only under
ignored `target/tooling/`. Earlier Docker discovery attempts failed while the
engine was stopped; those blockers were resolved before the successful full
suite. The GitHub Actions workflow was authored, not executed on GitHub.
The separate `tracelens-m1-check` smoke stack was stopped after validation; its
PostgreSQL volume was preserved. No user database was cleared or removed.

At the end of Milestone 1, not yet implemented: experiment runner, lab controls, injected origin delay,
single-flight mitigation, run-local evidence, reports, or OpenTelemetry/Jaeger.
There are no performance or production-readiness claims. No commits or pushes
have been made. Next approved scope ends at Milestone 1; review Milestone 2
before implementing it. Milestone 1 acceptance checks were complete on this
Windows/Docker Desktop host; cross-platform CI still needs its first GitHub run.

## 2026-10-28 — Milestone 2 controlled burst

Implemented the token-protected lab profile, isolated Compose runner,
single-instance per-key coalescing, synthetic PostgreSQL delay, session and
cohort admission/drain controls, run-local evidence, and JSON/Markdown reports.
Normal startup has no lab controls. The runner has a fixed Compose target.

Validation on Temurin 25.0.5+8 and Docker Desktop Linux containers:

- `mvnw.cmd verify` passed 25 unit and 18 integration cases after the final
  application changes, including real Redis/PostgreSQL, forced concurrent misses,
  follower failure/timeout/interruption, session isolation, and drain behavior.
- A targeted `mvnw.cmd verify -Dit.test=LabApiIT` also exercised the injected SQL
  timing and an actual Redis cache-write failure shared by seven waiting followers,
  followed by a successful recovery cohort.
- The final Compose image built and became healthy. Its default 32-request,
  five-repetition run passed all 15 cohorts and generated both report formats.
  In this one synthetic local run, each baseline cohort recorded 0 product SQL
  attempts, each failure cohort 32, and each mitigation cohort 1; failure-mode
  peak client-side SQL overlap was 16. These observations are not production
  performance claims.
- A one-request run produced a valid inconclusive report and exit code 2. A
  zero-concurrency input produced an invalid report and exit code 1. The default
  passed run returned 0. The passed and inconclusive JSON and Markdown files were
  inspected; the passed report was checked for the lab token.
- The local smoke stack was stopped after verification; its PostgreSQL volume
  was preserved. The generated reports under ignored `reports/` remain available.

The GitHub Actions lab smoke step has been authored but has not run on GitHub.
At the end of Milestone 2, tracing, sustained-load testing, and distributed
coalescing were unimplemented. One instance, one key, injected delay, small burst samples, and
shared-host resource contention limit interpretation. No commit or push was made.

## 2026-10-28 — Milestone 3 trace evidence

Added opt-in agent-based tracing for the runner and application, explicit
cache/coalescing/origin spans, an isolated Jaeger override, trace IDs and
instrumentation settings in reports, and an automated trace inspection script.
The ordinary application and lab remain usable without the agent.

Validation on Temurin 25.0.5+8 and Docker Desktop Linux containers:

- `mvnw.cmd verify` passed 26 unit and 18 real infrastructure integration cases,
  with no failures, errors, or skips. The tracing unit test checks parentage,
  cache outcome attributes, and error status without recording exception text.
- The traced Compose stack built and became healthy after correcting the agent
  file permissions for the non-root runtime. An eight-request, one-repetition
  experiment passed all three cohorts and wrote both report formats. The Jaeger
  inspection script verified runner-to-server, cache, SQL, and follower-wait
  relationships and checked the inspected traces and reports for the lab token.
- With Jaeger stopped, another eight-request experiment passed (baseline 0,
  failure 8, mitigation 1 product SQL attempts); reports correctly marked trace
  delivery as unverified. Export errors were visible in agent logs.
- Restarting the lab without the tracing override produced a valid one-request
  inconclusive report, with tracing disabled and no trace IDs. This confirms the
  no-agent path and does not establish performance equivalence.

The GitHub Actions tracing and outage checks have been authored but not run on
GitHub. Separate Lettuce command spans were not observed in this stack; explicit
cache spans describe Redis behavior. Jaeger uses transient in-memory storage.
The lab remains single-instance and synthetic; no production or performance
claims follow from these observations. No commit or push was made.
