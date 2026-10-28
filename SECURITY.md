# Security policy

TraceLens is an early local lab, not a hardened production service. No released
version currently has a security-support commitment. Keep it on loopback with
isolated synthetic data. Do not point it at production or share production
credentials. There is no authentication; health and metrics are local endpoints.

Do not publish credentials, exploit details affecting other users, or private
incident data in issues. A public repository URL and private reporting channel
have not yet been established; a maintainer must publish that channel before
public release. No private-response SLA is currently promised.

Dependencies and container images are pinned for repeatability, not automatically
updated. Review advisories and rerun validation when updating them. The optional
lab profile exposes token-protected controls; keep its token private and use the
included isolated Compose network. The runner has a fixed Compose target. Docker
host administrators and other processes with access to the token are trusted.
The optional Jaeger UI has no authentication and is bound to loopback. Traces
include synthetic request and database details; do not expose the UI or use
production data. The agent is not configured to capture authentication headers.
