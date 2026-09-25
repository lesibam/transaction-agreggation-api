# Lessons Log

Owned by the Meta-Agent (Continuous Improvement), defined in [`claude.md`](../../claude.md).

This is an **append-only** log. Each entry records a recurring failure pattern
observed across PR reviews, CI runs, or production incidents, and — where
warranted — the resulting change to an agent's `Key Focus` or `Ownership` in
`claude.md`. Do not edit or delete past entries; if a lesson is later found to
be wrong or superseded, add a new entry that says so and links back.

A retrospective runs whenever a phase in [`IMPLEMENTATION_PLAN.md`](../../IMPLEMENTATION_PLAN.md)
reaches "Done" (§6) and after any production incident. Only add an entry when
a pattern is *recurring* (seen 2+ times) or caused a real incident — one-off
mistakes belong in the PR/postmortem, not here.

## Entry template

```
### YYYY-MM-DD — <short title>
- **Pattern observed**: <what kept happening, with evidence links (PR #, CI run, postmortem)>
- **Agent(s) involved**: <name(s) from claude.md>
- **Root cause**: <the gap in Key Focus / Ownership / process that allowed it>
- **Proposed change**: <the exact diff to claude.md, or "none — logged for awareness">
- **Coordinator decision**: <approved / rejected / deferred, with date>
```

## Entries

### 2026-09-24 — Non-blocking review findings don't converge without a forcing function
- **Pattern observed**: The 2026-09-23 principal engineer review (`docs/principal_engineer_review_report.md`) logged 3 High, 7 Medium, and 8 Low findings. A follow-up review one day later (`docs/principal_engineer_review_report.md` §10) found all 3 High (P0) findings fixed, and **all 7 Medium + 8 Low findings unchanged, byte-for-byte** — every commit in between (`582831f`, `4807e23`, `d1f7ae6`) touched only CI/process, never application code. This is recurring by construction: it is the default outcome for every finding that isn't a sign-off gate, and will repeat for this review's own new Medium/Low items unless something changes.
- **Agent(s) involved**: Testing Engineer, Coordinator (findings ownership); all agents (as the ones who'd action a fix in their owned paths).
- **Root cause**: The Definition of Done (`IMPLEMENTATION_PLAN.md` §6) only ever required a *retrospective to run and log findings* (step 6, added 2026-09-24) — it never required open Medium findings to be re-triaged (fixed, explicitly deferred with a reason, or downgraded) before a phase counts as Done. Logging without a re-triage step just accumulates a list that nobody is on the hook to clear.
- **Proposed change**: Add a step to `IMPLEMENTATION_PLAN.md` §6 Definition of Done: each phase retrospective must re-triage every open Medium/Low finding touching that phase's owned paths — fix, explicitly re-defer with a reason, or close as won't-fix — rather than only logging new ones.
- **Coordinator decision**: Deferred — proposed here for the project owner to accept or reject; not applied automatically since it changes the Definition of Done (a stricter delivery bar), not just an agent's descriptive scope.

### 2026-09-24 — New infrastructure wasn't checked against the guide's own "What Not to Build" list
- **Pattern observed**: The project added a Helm chart and a `deploy-demo` CI job targeting Kubernetes for the demo environment, with no ADR. The requirements source (`transaction-aggregation-staff-engineer-guide-2.md` §85 "What Not to Build" and §96K/§96L) explicitly names Kubernetes as scope to avoid for a demo tier "unless the requirements specifically demand them," and recommends Compose or a small VPS instead. Every other infrastructure decision in the tree (Postgres, Kafka topology, eventual consistency, the messaging port) has a matching ADR in `docs/adr/`; this is the one that doesn't. First occurrence — logging now because the fix is cheap and preventive, not because a second instance has been observed yet (see the log's own recurrence rule above).
- **Agent(s) involved**: Architect (owns `docs/adr/`, `infrastructure/`, and technology-strategy calls).
- **Root cause**: The Architect's `Key Focus` in `claude.md` didn't name the requirements doc's own explicit anti-pattern list as something to check new infrastructure against before adding it — only "Scaling strategies and Infrastructure-as-Code" in the abstract.
- **Proposed change**: Add a bullet to the Architect's `Key Focus` in `claude.md`: cross-check new infrastructure (deployment targets, new datastores, new brokers) against the guide's "What Not to Build" list (§85) before introducing it, and write the justifying ADR when a listed item is genuinely needed.
- **Coordinator decision**: **Approved and applied 2026-09-24** — low-risk, additive, descriptive-only change to one agent's `Key Focus`; applied directly in `claude.md` as part of this review.

### 2026-09-25 — Agent Ownership paths drift when a change lands outside the agent workflow
- **Pattern observed**: `origin/main` gained a commit (config-driven source registry, ADR-011) authored directly by the project owner, not through any agent session. It added `config/SourceDescriptor.java` and `config/SourceRegistryProperties.java` alongside the adapter/normalizer code Integration Engineer already owned — but `claude.md`'s Ownership paths were never updated to include them, since no agent turn ever touched `claude.md` as part of that change. A rebase reconciling this branch with `main` is what surfaced it: side-by-side comparison against the real tree, not a normal review pass, is what caught the gap. The same sweep also found three pre-existing, unrelated Ownership gaps: `mock-s3/` (S3 demo seed data, referenced directly by `docker-compose.yml`'s `minio-seed` service) and `dev.sh` (the local dev/demo entrypoint) were never assigned to any agent, and Architect's `infrastructure/` Ownership entry never corresponded to a real path — it predates Terraform ever being introduced (still not built) and collided with the Java `infrastructure/` package other agents already own pieces of.
- **Agent(s) involved**: Integration Engineer (`config/SourceDescriptor.java`, `config/SourceRegistryProperties.java`), Operations/SRE Engineer (`mock-s3/`, `dev.sh`), Architect (`infrastructure/` ambiguity), Testing Engineer (`scripts/mint-jwt.mjs`, `scripts/e2e-assert.mjs`, `scripts/test.sh` — never assigned despite `scripts/load-test.js` already being Testing Engineer's).
- **Root cause**: Ownership paths only ever get updated as a side effect of an agent's own PR touching `claude.md`. Work that lands directly on `main`, or any other out-of-band change, has no natural trigger to update this document, so drift accumulates silently until something forces a side-by-side comparison against the actual tree.
- **Proposed change**: Fixed directly in `claude.md` (Integration Engineer, SRE, Architect, Testing Engineer Ownership lines) as part of this review — each is a low-risk, additive, descriptive-only correction. Longer-term: the Meta-Agent's own periodic drift-check (`Key Focus` bullet 5) should include an explicit repo-tree diff against every agent's listed Ownership paths, not rely on a rebase or similar event to trigger it.
- **Coordinator decision**: **Approved and applied 2026-09-25** — same low-risk bar as the 2026-09-24 precedent above.
