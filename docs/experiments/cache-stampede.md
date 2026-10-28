# Cache-stampede experiment specification

The lab implementation uses a separate Compose runner and opt-in application
profile. See README.md for commands and PROGRESS.md for actual validation.

## Hypothesis

Concurrent misses for one key can produce duplicate origin reads. Per-key
coalescing should reduce a successful cold refill to one origin read inside one
application instance. This does not prove coordination across replicas.

## Workload and conditions

Use one existing synthetic product, 32 HTTP workers, one request each, released
through a client barrier; fixed database pool 16; maximum server threads 64;
cache TTL 300 s; HTTP deadline 5 s; cohort drain deadline 10 s; five measured
cohorts per mode. Configure 100 ms of PostgreSQL-side delay in the measured
product SQL for every mode. It is synthetic service time, not CPU/I/O pressure.

| Mode | Preparation | Coalescing | Expected successful result |
| --- | --- | --- | --- |
| Baseline | Warm and verify key | Off | Zero measured origin reads |
| Failure | Delete owned key; verify absent | Off | Multiple overlapping origin reads |
| Mitigation | Same deletion/verification | On | Exactly one origin read |

Warm JVM and connections consistently before measurement; exclude preparation
requests. Rotate mode order across repetitions. Only one cohort runs at a time.
Drain all work before reset; failed drain invalidates the cohort and prevents
reuse of contaminated evidence. Delete owned keys only; never FLUSHDB/TRUNCATE.
Record preparation, release, first miss, refill completion, and drain events.

## Coalescing semantics

After an initial miss, atomically join/create a per-key in-flight future. The
leader rechecks Redis, queries the origin only if still missing, writes Redis,
then completes the future. Followers share the result/failure. Remove only the
matching in-flight entry on every terminal path. Follower timeout must not cancel
the shared load. Bound dependency operations and waiting; test leader failure,
later recovery, independent keys, and absence of abandoned flights.

## Evidence and validation

Use exact run-local counters and monotonic time. Record scheduled/dispatched/
admitted/completed/failed/timed-out requests; initial and secondary cache reads;
initial and secondary cache-read errors; waiters; cache write failures; product SQL attempts/successes/failures; connection
wait and SQL time; current/peak outstanding client-side SQL; dispatch timing.
No retry is part of this experiment.

- Reads/request = SQL attempts / admitted experiment requests.
- Cold refill amplification = SQL attempts / 1 expected ideal refill.
- Excess reads = max(0, SQL attempts - 1) for a valid cold cohort.
- Never divide by healthy-baseline query count, which should be zero.
- Client latency spans send initiation through response-body completion/timeout.
- Report success-only and all-terminal-outcome latency separately, plus errors.
- Use nearest-rank percentiles (`ceil(p * N)`, one-based) for P50/P95/P99, raw
  sample counts, and per-cohort distributions. Do not average percentiles.

A valid mitigation cohort requires correct responses, exactly one successful
origin read, and no leftover flight. A client barrier does not guarantee server
simultaneity: lack of overlap in failure mode means "not demonstrated", not a
silent configuration change. Export configuration, versions, host/resource
metadata, raw evidence, assertions, invalidity reasons, and limitations as JSON
and Markdown. Distinguish completed execution, hypothesis outcome, and validity.
Request IDs in raw client samples match the correlation IDs in application logs.
Server lifecycle times are monotonic offsets from preparation; client dispatch
offsets are measured in the runner and cannot be directly aligned with server
timestamps. Reports label configured image versions and runner environment.

With the optional tracing override, per-request trace IDs link reports to
Jaeger. Explicit cache and origin spans explain the request path, and follower
waits appear in their own traces. Trace sampling and exporter availability do
not determine assertions; reports do not claim delivery to Jaeger.

## Limitations

One instance, one key, synthetic data and origin delay, shared host, and a small
burst. P99 with 32 observations is effectively an extreme sample. Cohort
completion rate is not sustained throughput. Explicit invalidation models the
refill race after expiry, not Redis expiry scheduling. Instrumentation and JVM
warmup affect timing. Report observed amplification without performance claims.
