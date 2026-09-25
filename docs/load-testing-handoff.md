# Handoff: Load Testing — Results Template & Interpretation Guide

| | |
|---|---|
| **From** | Testing Engineer (owns `scripts/load-test.js` per `claude.md` #8) |
| **To** | Operations/SRE Engineer |
| **Status** | **A first run exists (2026-09-24) and passed clean — but neither of §2's scoping gaps is closed, and this handoff's own §3 reporting convention hasn't been used for it yet.** `IMPLEMENTATION_PLAN.md` Phase 7 "Load Testing" is `[x]`: `scripts/load-test.js` was run against the compose stack at `load` (20 VUs, ramped) and `soak` (8 VUs, 5 min) — zero failures, p99 ≤ 24 ms / p95 ≤ 21 ms on both SLI endpoints, comfortably inside the Phase 6 SLO targets. That's real evidence the pipeline holds up under moderate concurrency; it is **not** yet evidence of capacity at any target scale (§2.1 — one VUS level per scenario, not a sweep) or against realistic data volume (§2.2 — still open). The script itself has also changed substantially since this handoff was first written (see §1) — the "two-scenario, VUS=10" description below is current, not the earlier thinner script. |

---

## 1. What exists today

- `scripts/load-test.js` — k6 script, three scenarios selected via `SCENARIO=smoke|load|soak|all` (default `all`): `smoke` (1 VU, 5 iterations — pipeline sanity), `load` (ramping 0 → `LOAD_TEST_VUS`, default 20, hold `LOAD_TEST_HOLD`, default 2m), `soak` (constant `SOAK_VUS`, default 8, for `SOAK_DURATION`, default 5m — watches for degradation over time: connection leaks, GC). Each iteration: 60% `GET /transactions` (30% of those filtered by category, following one keyset cursor hop when `meta.hasMore`), 40% `GET /summary`. Mints its own HS* JWT in `setup()` (mirrors `scripts/mint-jwt.mjs`, alg chosen from secret length) unless `LOAD_TEST_TOKEN` is supplied. Thresholds encode the Phase 6 SLO targets as separate per-endpoint `Trend` metrics (`transact_txn_duration`, `transact_summary_duration`, both `p(99)<200ms`) plus `http_req_failed rate<0.001` and a structural-checks pass rate — a blended `http_req_duration` threshold can't hide one slow endpoint behind a fast one, which is why the script tracks them separately.
- `./dev.sh perf [--scenario smoke|load|soak|all] [--vus N] [--hold Ns] [--customer-id ID] [--tls]` — mints a token (via the real `APP_SECURITY_JWT_SECRET`, not the script's own test-only default), runs a local `k6` binary if present, otherwise falls back to the official `grafana/k6` Docker image automatically. No local k6 install required either way. Default scenario is `smoke` (fast pipeline sanity, ~10s) — pass `--scenario load|soak|all` for a real capacity run.
- k6 itself enforces pass/fail via its threshold mechanism (non-zero exit on breach) — this is not a "run it and eyeball the numbers" tool; a passing exit code already means something.

## 2. Two scoping gaps to close before trusting any result

**These are not blockers to running the script once — they're blockers to treating the result as a real capacity signal.** Run it as-is first to shake out plumbing bugs; then close these before using the numbers for any actual decision.

### 2.1 One VUS level tested per scenario, not a sweep
`./dev.sh perf`'s own default is still `smoke` (fast pipeline sanity, ~10s) — the real capacity scenarios (`load`, `soak`) require an explicit `--scenario` flag. The 2026-09-24 run did exercise `load` (20 VUs) and `soak` (8 VUs), not just smoke, so that part of this gap is closed. What's still missing:
- **Only one VUS level per scenario has been run.** Run `load` at more than one `--vus` level (e.g., 20 / 50 / 200) to see where latency starts degrading, not just whether it degrades at the one level tested — a single data point can't distinguish "healthy at this scale" from "coincidentally fine at exactly 20 VUs."
- Pick a target concurrency grounded in something real. Nothing in this repo has stated an expected production QPS/concurrent-user number (this is a demo/assessment project — there is no real traffic baseline to derive one from). SRE and Testing Engineer should agree an explicit assumption (e.g., "size for N concurrent API clients") and document it in the results report itself, not leave the number unexplained.

### 2.2 Test data volume is effectively empty
The script only exercises the Flyway-seeded demo customer (`00000000-0000-0000-0000-000000000001`), whose `transactions` table holds whatever the registry-driven sources have produced by test time — a handful to a few hundred rows under the committed default registry (three `MOCK` sources), not production-representative volume. **This is the more important gap of the two, and it is still open**: the 2026-09-24 run's `IMPLEMENTATION_PLAN.md` note doesn't state the data volume at run time, so it should be assumed thin (default-registry scale) until someone confirms otherwise — do not treat that run's p99 as evidence at real scale. Keyset pagination's whole performance argument (`docs/04-api-contract.md`, ADR-relevant) is that it stays O(limit) as a table grows — but a load test against a near-empty table cannot prove that claim; it can only prove the API doesn't fall over under concurrent requests against data that fits trivially in a few index pages.

**Before results are meaningful for capacity planning**: seed a realistic transaction volume for the test customer (order of magnitude matching whatever scale assumption Architect/Domain Expert have used elsewhere — the Staff Engineer Guide's own worked examples are the natural reference point if no other number exists) and re-run. Report *both* the near-empty-table run (cheap, fast, good for regression-catching in CI — see §5) and the realistic-volume run (the one capacity decisions should actually use) as separate rows, never conflated into one number.

## 3. Results report template

Whoever runs this should fill in the table below — copy it into a dated results doc (`docs/load-test-results/YYYY-MM-DD.md`, one per run, not overwritten) rather than editing this handoff in place, so results accumulate as a trend instead of replacing each other.

> **2026-09-24 run**: `IMPLEMENTATION_PLAN.md` Phase 7 already records real numbers for this date — load: 20 VUs, ~20 rps, 5,467 reqs, 0 failures, p99 ≤ 24 ms on both SLI endpoints; soak: 8 VUs / 5 min, 3,353 reqs, 0 failures, p95 ≤ 21 ms — but as an inline plan note, not transcribed into this template's dated-file convention. Environment specifics, exact test data volume, and per-endpoint p50/p90/p95 breakdowns aren't captured there. Backfilling `docs/load-test-results/2026-09-24.md` from what's known (marking the rest "not captured at run time") is lower priority than closing §2's gaps with a fresh run — but flagged here so the *next* run isn't reported as "the first one."

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

- [ ] `./dev.sh perf` (or an equivalent direct `k6 run`) has produced a filled-in §3 report, committed to `docs/load-test-results/`. **Partially done**: the 2026-09-24 load+soak run has real, clean results, but they live as an `IMPLEMENTATION_PLAN.md` inline note, not a dated file per §3's convention — see the callout there.
- [ ] §2.1's concurrency target (a VUS sweep, not one level) and §2.2's realistic data volume have been agreed (Testing Engineer + SRE) and a realistic-scale run has been reported separately from the 2026-09-24 default-registry-scale run.
- [ ] SRE has decided §5's CI question and, if a `perf-smoke` job is added, it follows the same "zero ambiguity about pass/fail" discipline `e2e-smoke` already established (a skipped or errored run must never read as green — same principle, different job).
- [ ] The DB pool saturation threshold in `docs/observability-alerting-handoff.md` §3.6 has been revisited with a real measured number, or explicitly left as a pre-measurement estimate with that fact stated in the alerting handoff itself.
