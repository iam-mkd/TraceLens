# Contributing

Start with README.md, AGENTS.md, docs/ARCHITECTURE.md, and docs/PROGRESS.md.
Discuss significant architecture or dependency changes before implementing them.
Keep changes focused, document trade-offs, and include meaningful behavior tests.
Use only synthetic data and isolated local services.

Install JDK 25, start Docker, and run `./mvnw verify` (Windows:
`mvnw.cmd verify`). Unit tests use Surefire; `*IT` tests use Failsafe and real
Redis/PostgreSQL Testcontainers. Do not skip failing tests to obtain a green
build. State environment blockers and commands actually run in your change.

Keep documentation aligned with implemented behavior. No benchmark claims
without reproducible inputs and evidence. Contributions are licensed under
Apache-2.0. A hosted issue tracker, maintainer contact, and release process have
not been established yet.
