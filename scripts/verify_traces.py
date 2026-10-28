"""Check a traced Compose report against the local, transient Jaeger store."""
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def fetch(trace_id):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:16686/api/traces/{trace_id}", timeout=3) as response:
            data = json.load(response).get("data", [])
            return data[0] if data else None
    except (urllib.error.URLError, TimeoutError):
        return None


def ancestor(spans, child, parent):
    by_id = {span["spanID"]: span for span in spans}
    seen = set()
    while child["spanID"] not in seen:
        seen.add(child["spanID"])
        refs = [ref["spanID"] for ref in child.get("references", []) if ref["refType"] == "CHILD_OF"]
        if not refs or refs[0] not in by_id:
            return False
        if refs[0] == parent["spanID"]:
            return True
        child = by_id[refs[0]]
    return False


def main():
    path = pathlib.Path(sys.argv[1])
    raw = path.read_text(encoding="utf-8")
    report = json.loads(raw)
    token = os.environ.get("TRACELENS_LAB_TOKEN")
    if token:
        check(token not in raw and token not in path.with_suffix(".md").read_text(encoding="utf-8"), "token in report")
    check(report["tracing"]["configured"] and report["tracing"]["traceContextObserved"], "runner trace context missing")
    check(report["applicationTracing"]["configured"] and report["applicationTracing"]["traceContextObserved"], "application trace context missing")
    check(report["tracing"]["delivery"] == "NOT_VERIFIED", "report must not claim delivery")
    check(report["outcome"] in ("PASSED", "INCONCLUSIVE"), "experiment invalid or failed")
    found = set()
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline and found != {"baseline", "failure", "mitigation", "follower"}:
        for cohort in report["cohorts"]:
            mode = cohort["mode"]
            for sample in cohort["samples"]:
                trace = fetch(sample["traceId"])
                if not trace:
                    continue
                if token:
                    check(token not in json.dumps(trace), "token in trace")
                spans = trace["spans"]
                names = {span["operationName"] for span in spans}
                roots = [s for s in spans if s["operationName"] == "tracelens.experiment.request"]
                servers = [s for s in spans if s["operationName"] == "GET /api/products/{id}"]
                caches = [s for s in spans if s["operationName"] == "tracelens.cache.lookup"]
                if not (roots and servers and caches and ancestor(spans, servers[0], roots[0]) and ancestor(spans, caches[0], servers[0])):
                    continue
                processes = trace["processes"]
                check(processes[roots[0]["processID"]]["serviceName"] == "tracelens-runner", "wrong runner service")
                check(processes[servers[0]["processID"]]["serviceName"] == "tracelens-app", "wrong application service")
                if mode == "baseline":
                    found.add(mode)
                origins = [s for s in spans if s["operationName"] == "tracelens.origin.load"]
                sql = [s for s in spans if s["operationName"].startswith("SELECT tracelens.products")]
                if origins and sql and ancestor(spans, origins[0], servers[0]) and ancestor(spans, sql[0], origins[0]):
                    found.add(mode)
                if mode == "mitigation" and "tracelens.coalescing.wait" in names and not origins:
                    found.add("follower")
        if found != {"baseline", "failure", "mitigation", "follower"}:
            time.sleep(1)
    check(found == {"baseline", "failure", "mitigation", "follower"}, f"missing trace evidence: {sorted({'baseline', 'failure', 'mitigation', 'follower'} - found)}")
    print("Verified runner, HTTP, cache, origin SQL and follower trace relationships in Jaeger.")


if __name__ == "__main__":
    main()
