# Roadmap

## Milestone 1 — Foundation

Real product HTTP API, Redis cache-aside, PostgreSQL fixture, bounded dependency
operations, logs/metrics, Testcontainers tests, Docker Compose, Maven Wrapper,
CI, and continuity documentation. Validation status is in PROGRESS.md.

## Milestone 2 — Controlled burst

CLI, lab-only preparation controls, origin delay, run-local measurements,
single-instance per-key coalescing, and JSON/Markdown comparison reports.
Cold/warm/mitigation, leader/follower failure, drain, and outcome behavior are
covered by tests. See PROGRESS.md for validation and limitations.

## Milestone 3 — Trace evidence

Optional OpenTelemetry agent and Jaeger, experiment-linked traces, recorded
instrumentation settings, and validation of context propagation and exporter
outage behavior. See PROGRESS.md for the validation performed.

## Milestone 4 — External usefulness validation

Have external Java engineers run the quickstart and explain reports. Address
setup and interpretation issues before expanding scenarios. Add sustained-rate
testing only with generator capacity and dropped-arrival reporting.

Deferred: retry storms, synthetic duplicate payments, distributed coalescing,
React UI, Kafka, Kubernetes, cloud hosting, and a plugin framework.
