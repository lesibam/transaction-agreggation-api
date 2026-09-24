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

_No entries yet. The first retrospective should run at the next phase
completion or incident, per the Continuous Improvement Loop in `claude.md`._
