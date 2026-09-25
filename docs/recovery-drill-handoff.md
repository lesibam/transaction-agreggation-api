# Handoff: Recovery Drill — Post-Restore Verification

| | |
|---|---|
| **From** | DBA (schema/constraint correctness on restore) and Data Reconciliation Engineer (data correctness via the reconciliation job) |
| **To** | Operations/SRE Engineer (owns `docs/recovery-plan.md` and executing the actual drill) |
| **Status** | **Proposed.** `docs/recovery-plan.md` already exists as SRE's own honest, explicitly-untested runbook (*"STATUS: DRAFT RUNBOOK — NOT EXECUTED"*) — this handoff doesn't replace it or duplicate it. It fills the one gap in it that isn't SRE's own domain expertise: §3.1 step 4 and §4's checklist both say, in effect, "verify integrity" without saying precisely *how* — that's this handoff. |

---

## 1. Why this is a handoff, not just an edit SRE makes alone

Same shape as the other four in `docs/handoffs-index.md`: SRE knows *how* to restore a database (snapshot/WAL mechanics, timing, the infrastructure side) but "did the restore preserve correctness" isn't an infrastructure question — it's a schema-invariant question (DBA's domain) and a source-of-truth-matching question (Data Reconciliation Engineer's domain, once that job exists). `docs/recovery-plan.md` §3.1 step 4 currently reads *"Run data-consistency checks on `transactions` (row counts, `UNIQUE(source_id, source_transaction_id)` re-validation, aggregate spot-checks)"* — a correct instinct, but not specific enough to actually execute during a drill without someone reverse-engineering what "aggregate spot-checks" means at 2am during an incident.

## 2. DBA's contribution: what "integrity" actually means for this schema

Every constraint across all three migrations, named precisely so a drill runbook can check each one directly rather than relying on `ddl-auto: validate` alone (which confirms Hibernate's *mapping* matches the schema — it does not confirm the data satisfies every constraint, only that the constraints themselves exist):

| Constraint | Migration | What it guards | Post-restore check |
|---|---|---|---|
| `idx_unique_source_transaction` (UNIQUE index) | V1 | `(source_id, source_transaction_id)` — the entire idempotency guarantee | `SELECT source_id, source_transaction_id, COUNT(*) FROM transactions GROUP BY 1,2 HAVING COUNT(*) > 1` returns zero rows |
| `accounts_customer_id_fkey` (unnamed FK, V1) | V1 | `accounts.customer_id → customers.id` | `SELECT COUNT(*) FROM accounts a LEFT JOIN customers c ON a.customer_id = c.id WHERE c.id IS NULL` returns 0 |
| `transactions_account_id_fkey` (unnamed FK, V1) | V1 | `transactions.account_id → accounts.id` | Same pattern, against `accounts` |
| `fk_transactions_customer` | V2 | `transactions.customer_id → customers.id` (denormalized column) | Same pattern, against `customers` — **also** cross-check it agrees with the account's own customer: `SELECT COUNT(*) FROM transactions t JOIN accounts a ON t.account_id = a.id WHERE t.customer_id != a.customer_id` returns 0 |
| `chk_transactions_amount_positive` | V2 | `amount > 0` | `SELECT COUNT(*) FROM transactions WHERE amount <= 0` returns 0 (should be structurally impossible if the constraint restored correctly — a nonzero count means the constraint itself didn't survive the restore, not that data is merely wrong) |
| `chk_source_sync_state_status` | V2 | `status IN ('INITIAL','SUCCESS','FAILED')` | Same pattern against `source_sync_state.status` |
| `chk_transactions_direction` | V4 | `direction IN ('DEBIT','CREDIT')` | Same pattern against `transactions.direction` |

**The non-obvious one, missing from `docs/recovery-plan.md` entirely today: Flyway migration replay.** If the restored snapshot predates a migration (e.g., a snapshot taken before V4 shipped, restored into an environment running today's application code), Flyway's own schema history table (`flyway_schema_history` — itself just a table, backed up along with everything else) needs to correctly detect the gap and apply V4 forward on startup, *before* `ddl-auto: validate` gets a chance to run. A drill should explicitly include: **restore an intentionally old snapshot, boot the current application version against it, and confirm Flyway applies the missing migrations rather than the app failing validation or silently running against a partially-current schema.** This is arguably the single most realistic disaster scenario for this project specifically (a snapshot is, by definition, from before "now"), and it's untested even in the current recovery-plan.md draft.

## 3. Data Reconciliation Engineer's contribution: reconciliation as restore evidence

Once the reconciliation job exists (`docs/reconciliation-alerting-handoff.md` — also not built yet), running it immediately after a restore is the strongest available evidence that the restore didn't just *boot*, but actually preserved *correct* data — stronger than any row-count spot-check, because it compares against each source's own reported state rather than only checking internal self-consistency. Concretely: **step 4 of a drill should be "trigger one reconciliation pass for every source and confirm zero drift," not a bespoke one-off query.**

This creates an explicit, honest dependency worth stating plainly: **a fully rigorous recovery drill is blocked on two things that don't exist yet** (the reconciliation job, and the drill itself never having run). This handoff doesn't pretend otherwise. If SRE wants to run a drill *before* reconciliation is built, §2's constraint checks plus the existing row-count/aggregate spot-checks in `docs/recovery-plan.md` are a reasonable interim substitute — weaker evidence (they can't catch a restore that's internally consistent but doesn't match the source's own numbers), but real evidence, and worth running rather than waiting on a second piece of unbuilt work to block a first drill indefinitely.

## 4. Proposed replacement text for `docs/recovery-plan.md`

SRE's call whether to apply these verbatim; written so they can be pasted in directly rather than requiring SRE to translate this handoff into runbook language:

**§3.1 step 4, replacing the current one-line "Verify Integrity":**
> 4. **Verify Integrity**: Run all seven constraint checks in `docs/recovery-drill-handoff.md` §2 (zero violations expected — a nonzero count on any of them means the constraint itself failed to restore, not merely that data is wrong). Confirm Flyway applied any migrations postdating the snapshot (check `flyway_schema_history`, not just that the app booted). Trigger one reconciliation pass per source and confirm zero drift (`docs/reconciliation-alerting-handoff.md`) — if the reconciliation job doesn't exist yet at drill time, fall back to the row-count/aggregate spot-checks below and note the gap in the drill report.

**§4 checklist, adding three items** (keep the existing seven as-is):
> - [ ] All seven schema constraints (`docs/recovery-drill-handoff.md` §2) re-verified with zero violations post-restore — **not yet verified**.
> - [ ] Flyway correctly applied migrations postdating the restored snapshot, confirmed via `flyway_schema_history`, not just a successful boot — **not yet verified**.
> - [ ] Reconciliation pass run post-restore with zero drift per source, or the gap explicitly noted if reconciliation isn't built yet at drill time — **not yet verified**.

## 5. Drill report template

Same pattern as `docs/load-testing-handoff.md` §3 — a dated report, not an edit to this handoff or to `docs/recovery-plan.md` itself:

| Field | Value |
|---|---|
| Drill date | `TBD` |
| Scenario drilled (§3.1/3.2/3.3 of `docs/recovery-plan.md`) | `TBD` |
| Snapshot age at restore time | `TBD` — directly informs whether §2's Flyway-replay check is exercised at all |
| Measured RTO | `TBD` |
| Measured RPO (data-loss window) | `TBD` |
| §2 constraint checks: pass/fail per constraint | `TBD` |
| Flyway replay: migrations applied post-restore, matched expectation? | `TBD` |
| Reconciliation pass result (or: not run, reconciliation not built yet) | `TBD` |
| Anomalies observed | `TBD` |

File as `docs/recovery-drill-results/YYYY-MM-DD.md`, one per drill — same accumulate-don't-overwrite reasoning as the load-testing results template.

## 6. Cadence

`docs/recovery-plan.md` §2.1 already states an intent — *"Monthly restore tests into a staging environment have been designed as a practice but never executed"* — this handoff doesn't revise that number, only flags it as a decision SRE should explicitly reaffirm or change once a first drill's actual effort/cost is known, rather than carrying forward an untested cadence assumption indefinitely.

## 7. Acceptance criteria for this handoff

- [ ] A first drill has been run (even without reconciliation available yet, per §3's fallback) and produced a filled-in §5 report.
- [ ] `docs/recovery-plan.md` §3.1 and §4 have been updated per §4 above, or SRE has proposed different wording that covers the same three additions.
- [ ] The Flyway-replay scenario in §2 has specifically been exercised at least once — not just a same-version restore, which doesn't test the realistic case.
- [ ] If reconciliation still isn't built by the time of the first drill, that gap is stated explicitly in the drill report, not silently skipped.
