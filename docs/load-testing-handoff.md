# Handoff: Load Testing — Results Template & Interpretation Guide

| | |
|---|---|
| **From** | Testing Engineer (owns `scripts/load-test.js` per `claude.md` #8) |
| **To** | Operations/SRE Engineer |
| **Status** | **No results exist yet — this is not a results report.** `IMPLEMENTATION_PLAN.md` Phase 7 "Load Testing" is explicitly open: the k6 script exists and is runnable via `./dev.sh perf`, but has never been executed against a live stack in any environment this project's evidence log covers (no Docker daemon has been available in any session that touched this script so far). This document is the reporting contract for whoever runs it first, plus two scoping gaps that need closing *before* any result from it should be trusted for capacity planning. Fabricating numbers here to look complete would be exactly the "ran but didn't actually check anything" failure mode `docs/principal_engineer_review_report.md` C-01 exists to warn against — this handoff stays a template until a real run backs it. |

---

## 1. What exists today

- `scripts/load-test.js` — k6 script, two scenarios (`list_transactions` at full VUS, `summary` at VUS/3 staggered 2s later), default `VUS=10 DURATION=60s`, thresholds already encoding the Phase 6 SLO targets: `p(99)<200ms` overall and per-endpoint, `http_req_failed rate<0.001`.
- `./dev.sh perf [--vus N] [--duration Ns] [--customer-id ID]` — mints a token, runs a local `k6` binary if present, otherwise falls back to the official `grafana/k6` Docker image automatically. No local k6 install required either way.
- k6 itself enforces pass/fail via its threshold mechanism (non-zero exit on breach) — this is not a "run it and eyeball the numbers" tool; a passing exit code already means something.

## 2. Two scoping gaps to close before trusting any result

**These are not blockers to running the script once — they're blockers to treating the result as a real capacity signal.** Run it as-is first to shake out plumbing bugs; then close these before using the numbers for any actual decision.

### 2.1 Default config is smoke-level, not load-level
`VUS=10 DURATION=60s` is enough to prove the API responds correctly under light concurrency — it is not a load test in the capacity-planning sense. Before reporting results as *the* load test:
- Pick a target concurrency grounded in something real. Nothing in this repo has stated an expected production QPS/concurrent-user number (this is a demo/assessment project — there is no real traffic baseline to derive one from). SRE and Testing Engineer should agree an explicit assumption (e.g., "size for N concurrent API clients") and document it in the results report itself, not leave the number unexplained.
- Run at more than one VUS level (e.g., 10 / 50 / 200) to see where latency starts degrading, not just whether it degrades at the one level tested — a single data point can't distinguish "healthy at this scale" from "coincidentally fine at exactly 10 VUS."

### 2.2 Test data volume is effectively empty
The script only exercises the Flyway-seeded demo customer (`00000000-0000-0000-0000-000000000001`), whose `transactions` table holds whatever the mock adapters have produced by test time — a handful to a few hundred rows, not production-representative volume. **This is the more important gap of the two.** Keyset pagination's whole performance argument (`docs/04-api-contract.md`, ADR-relevant) is that it stays O(limit) as a table grows — but a load test against a near-empty table cannot prove that claim; it can only prove the API doesn't fall over under concurrent requests against data that fits trivially in a few index pages. A p99 measured here says nothing about p99 at real scale.

**Before results are meaningful for capacity planning**: seed a realistic transaction volume for the test customer (order of magnitude matching whatever scale assumption Architect/Domain Expert have used elsewhere — the Staff Engineer Guide's own worked examples are the natural reference point if no other number exists) and re-run. Report *both* the near-empty-table run (cheap, fast, good for regression-catching in CI — see §5) and the realistic-volume run (the one capacity decisions should actually use) as separate rows, never conflated into one number.

## 3. Results report template

Whoever runs this should fill in the table below — copy it into a dated results doc (`docs/load-test-results/YYYY-MM-DD.md`, one per run, not overwritten) rather than editing this handoff in place, so results accumulate as a trend instead of replacing each other.

| Field | Value | Source |
|---|---|---|
| Run date | `TBD` | — |
| Git commit | `TBD` | `git rev-parse HEAD` |
| Environment | `TBD` (local `./dev.sh start`? CI? a shared/staging stack?) | — |
| VUS / Duration | `TBD` | k6 invocation |
| Test data volume (transaction rows for test customer) | `TBD` | see §2.2 |
| `http_req_duration` p50 / p90 / p95 / p99 | `TBD` | k6 end-of-run summary |
| `http_req_duration{endpoint:list}` p99 | `TBD` | k6 summary (tagged) |
| `http_req_duration{endpoint:summary}` p99 | `TBD` | k6 summary (tagged) |
| `http_req_failed` rate | `TBD` | k6 summary |
| Total requests / requests per second | `TBD` | k6 summary (`http_reqs`) |
| Threshold verdict (k6 exit code) | `TBD` — pass/fail, and **by how much** (a threshold that barely passes is a different signal than one passing with 5x margin) | k6 exit code + summary |
| Checks pass rate (`list: status 200`, `summary: status 200`, `list: has meta.freshness`) | `TBD` | k6 summary |
| DB connection pool behavior during the run (`hikaricp_connections_active`/`.max`) | `TBD` | Grafana, if `docs/observability-alerting-handoff.md` §3.6 dashboard panel exists by then; otherwise pull from `/actuator/prometheus` directly | 
| Anomalies observed (errors in `docker compose logs transaction-api`, GC pauses, connection pool exhaustion) | `TBD` | — |

## 4. Feeding results back into the other handoffs

This is why the report format above matters — a load test that produces a number nobody uses is wasted effort:

- **`docs/observability-alerting-handoff.md` §3.6** proposed DB pool saturation alert thresholds (warning >80%, critical >95%) as a guess, not a measurement — nobody has ever pushed this pool toward exhaustion. A load test that watches `hikaricp_connections_active` under realistic concurrency turns that guess into a measured threshold. If the pool saturates well below the concurrency level being tested, that's also a capacity finding in its own right (Boot's default Hikari pool size, 10, is unconfigured anywhere in this repo — `application.yml`, `docker-compose.yml`, `helm/transact/values.yaml` — worth explicitly deciding, not inheriting by accident).
- **The p99 threshold itself** (`http_req_duration p(99)<200`) is the same number `docs/observability-alerting-handoff.md` §3.2 needs a real percentile histogram to alert on in production. A load test result and a production alert threshold should agree with each other — if the load test consistently passes at p99<200ms but production alerting is calibrated differently, one of the two is wrong.
- **Phase 9's HA topology decisions** (`IMPLEMENTATION_PLAN.md`, "Multi-Replica, Zero-Downtime Deploys") need a real capacity number per instance before "how many replicas" is anything but a guess — this is where that number comes from.

## 5. Open question for SRE: does this become a CI job?

No `perf-smoke` job exists in `.github/workflows/ci.yml` today — only `build-and-test`, `containerize` (with the blocking Trivy gate), and `e2e-smoke` (compose + Playwright, zero-skipped enforced). Two real options, SRE's call:

1. **Add a lightweight `perf-smoke` CI job** running the script at its smoke-level default (§2.1) on every merge to catch gross regressions early — cheap, fast, doesn't need the realistic-volume seeding from §2.2.
2. **Keep load testing manual/periodic**, run against a longer-lived environment with realistic data volume rather than a fresh CI container every time — better signal, worse regression-catching latency.

These aren't mutually exclusive (a cheap CI smoke gate plus a periodic realistic-volume run is a reasonable combination) — flagged as an open decision rather than prescribed, the same way the Alertmanager-vs-Grafana-alerting question was left to SRE in the other two handoffs.

## 6. Interpreting a threshold breach

If a run's thresholds fail, before treating it as a real performance regression, rule out the confounding factors specific to how this script actually runs (`./dev.sh perf`'s own header warns about this):

- **Where k6 itself ran matters.** The Docker-image fallback (`grafana/k6` via `docker run`) adds its own container overhead and, if running on the same host as the API under test, competes with it for CPU — a shared-host run will show worse numbers than k6 running from a separate machine, independent of anything the API did wrong.
- **A cold start.** The first requests after `./dev.sh start` hit a JVM that hasn't JIT-warmed and connection pools that haven't primed — a load test starting immediately after stack startup will show an artificially bad p50 for the first several seconds. Consider a short unmeasured warm-up period before the measured run.
- Only after ruling both out should a threshold breach be reported as a genuine finding, and at that point it's a real regression worth the same seriousness as a failing `e2e-smoke` run — see the drive-to-green discipline already established for that job.

## 7. Acceptance criteria for this handoff

- [ ] `./dev.sh perf` has been run at least once (even at smoke defaults) and produced a filled-in §3 report, committed to `docs/load-test-results/`.
- [ ] §2.1's concurrency target and §2.2's realistic data volume have been agreed (Testing Engineer + SRE) and a second, realistic-scale run has been reported separately from the smoke run.
- [ ] SRE has decided §5's CI question and, if a `perf-smoke` job is added, it follows the same "zero ambiguity about pass/fail" discipline `e2e-smoke` already established (a skipped or errored run must never read as green — same principle, different job).
- [ ] The DB pool saturation threshold in `docs/observability-alerting-handoff.md` §3.6 has been revisited with a real measured number, or explicitly left as a pre-measurement estimate with that fact stated in the alerting handoff itself.
