# AGENTS.md — TraceLens

## Project Identity

TraceLens is a local-first, open-source distributed-systems reliability toolkit.

It helps engineers reproduce failures, capture telemetry, validate resilience mechanisms, and generate evidence-backed experiment reports.

The initial MVP focuses on reproducing Redis cache stampedes.

Read `docs/PROJECT_VISION.md`, `docs/ARCHITECTURE.md`, and `docs/ROADMAP.md` when relevant. Do not assume these files exist until verified.

## Engineering Principles

- Prioritize correctness, reproducibility, and simplicity.
- Prefer a modular monolith over unnecessary microservices.
- Follow existing repository conventions before introducing new patterns.
- Avoid premature abstractions, speculative extensibility, and unnecessary dependencies.
- Do not introduce cloud infrastructure or paid services without explicit approval.
- Keep experiments local-first and safe by default.
- Never claim benchmark improvements without reproducible measurements.

## Technology Stack

Preferred technologies:

- Java 25
- Spring Boot
- Maven
- PostgreSQL
- Redis
- Docker Compose
- OpenTelemetry
- JUnit Jupiter 6 and Testcontainers (approved with Spring Boot 4.1.2)

Verify actual dependency versions and build commands from repository files.

Do not introduce Kafka, Kubernetes, or additional infrastructure unless needed for an approved milestone.

## Java Development Standards

- Use clear, descriptive names.
- Favor constructor injection.
- Use appropriate encapsulation and immutable data structures.
- Keep controllers thin and business logic testable.
- Handle exceptions explicitly where meaningful.
- Use structured logging where supported.
- Do not expose sensitive information in logs.
- Avoid unnecessary interfaces and design patterns.
- Document concurrency assumptions and thread-safety guarantees.
- Do not suppress compiler warnings without justification.

## Experiment Correctness

Every experiment must document:

1. The hypothesis being tested.
2. The workload configuration.
3. The failure being introduced.
4. The metrics collected.
5. The expected system behavior.
6. The assertions used for validation.
7. Known limitations.

Distinguish observations from hypotheses.

Do not silently alter workload configurations between benchmark comparisons.

Prefer repeatable tests over visually impressive but misleading results.

## Testing Requirements

- Add unit tests for meaningful business logic.
- Add integration tests for Redis, PostgreSQL, and concurrency behavior where appropriate.
- Prefer Testcontainers for infrastructure-dependent integration tests.
- Test failure conditions, not only successful execution paths.
- Never delete or weaken tests merely to make the build pass.
- Run the relevant test suite after implementation changes.
- If tests cannot run, explain why.

Once the Maven wrapper is available, prefer:

`./mvnw test`

On Windows, use:

`mvnw.cmd test`

Run `./mvnw verify` (Windows: `mvnw.cmd verify`) for the full acceptance suite.
Surefire runs unit tests; Failsafe runs `*IT` tests against disposable Redis and
PostgreSQL Testcontainers. Docker is required for `verify`; never silently skip
integration tests when the engine is unavailable. Java compilation targets 25.

Do not assume the wrapper or Docker environment exists without checking.

## Security and Safety

- Never commit credentials, tokens, or secrets.
- Never run destructive operations against external systems.
- Restrict fault injection to explicitly approved test environments.
- Require confirmation before destructive database operations.
- Do not access production infrastructure.
- Use synthetic data for all payment-related experiments.
- Never use proprietary employer or client source code, data, configuration, or logs.

## Working Agreement

For nontrivial changes:

1. Inspect the relevant code and documentation.
2. Explain the proposed approach and trade-offs.
3. Present a short implementation plan.
4. Obtain approval before major architectural changes.
5. Implement the smallest useful increment.
6. Run relevant tests.
7. Summarize changes, results, limitations, and next steps.

For small, explicitly requested changes, proceed without unnecessary approval steps.

Do not make unrelated refactors.

Do not create commits, push changes, or open pull requests without explicit authorization.

## Documentation

Maintain these files as the project evolves:

- `docs/PROJECT_VISION.md`
- `docs/ARCHITECTURE.md`
- `docs/DECISIONS.md`
- `docs/ROADMAP.md`
- `docs/PROGRESS.md`

Keep documentation aligned with actual implementation.

Do not claim a feature is implemented until it works and has appropriate verification.

## Definition of Done

A task is complete when:

- The requested behavior is implemented.
- Relevant tests pass, or blockers are documented.
- Important edge cases are addressed.
- Configuration changes are documented.
- No unrelated modifications are introduced.
- The final summary explains what changed and how it was verified.
