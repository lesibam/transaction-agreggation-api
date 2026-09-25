# ADR 013: Read-Only Web Dashboard, Toggleable Off in Production

## Status
Accepted

## Context
Every way to see this system's data today requires a terminal: `curl` with a
manually-minted JWT, `jq`, and knowledge of the API contract. That's fine for
the agents building this system, but a transaction-aggregation product's
actual users (ops, support, an internal reviewer) need to *see* the ledger,
the per-source freshness/completeness picture, and category breakdowns
without reading `docs/04-api-contract.md` first. The API itself (`/v1/**`)
must stay the only way anything writes or is enforced server-side — a UI on
top of it should add zero new authority, just a browser for what the API
already exposes.

## Decision
A static, read-only web dashboard is served at `/ui/**`, built as plain
HTML/CSS/JS with no build step and no new dependency, gated behind
`app.ui.enabled` (default `true`, independent of `app.docs.enabled` —
ADR-014) so a production deployment that doesn't want an internal tool
reachable can turn it off with one config value, with zero effect on the API
itself.

1. **No new authority.** The dashboard holds a JWT in `sessionStorage` and
   calls `/v1/**` with it, exactly like any other API client. Every request
   is authorized by the same `CustomerAccessValidator`/`SecurityConfig` rules
   already in place — the UI cannot see or do anything a `curl` call with the
   same token couldn't. There is no server-side session, no new stored
   credential, no privileged backend-for-frontend layer.
2. **Static assets, not a template engine.** Served from
   `src/main/resources/webapp/ui/` via a `WebMvcConfigurer` bean
   (`UiResourceConfig`) registered only when `app.ui.enabled=true` — not via
   Boot's default `src/main/resources/static/` auto-mapping, which is
   unconditional and would defeat the toggle (see Implementation).
3. **No login flow of its own.** The app's actual auth model is bearer-JWT,
   issued by whatever signs tokens in a given environment (a real IdP in
   production, `./dev.sh token` / `scripts/mint-jwt.mjs` locally — see
   `claude.md`'s Enterprise Identity & Access item, Phase 9, for what a real
   IdP integration still needs). Building a username/password form the
   backend has no way to honor would be dishonest UI: the dashboard's "sign
   in" is pasting the token you already have, matching what's actually true
   about how this API authenticates today.
4. **Design language grounded in the subject.** A ledger/bank-statement
   visual vocabulary (hairline rules, tabular monospace figures, a single
   functional colour axis for freshness/completeness status) rather than a
   generic admin-template look — detailed reasoning and the token system in
   the dashboard's own `styles.css` header comment.

## Alternatives Considered
- **React/Vite SPA, built at Docker-build time**: rejected for a first pass —
  a real build stage in `Dockerfile`/CI is a bigger, harder-to-reverse
  commitment (new toolchain, new dependency surface, new failure mode in the
  image build) than this project's current scope justifies for an internal,
  read-only tool. Revisit if the dashboard's own complexity outgrows what
  vanilla JS can hold cleanly — not before.
- **Server-rendered pages (Thymeleaf)**: rejected — pushes rendering logic
  into the same process as the API for no benefit here (the data is already
  JSON over a documented contract); a thin JS client that calls the existing
  API is simpler and dogfoods the contract other integrators use.
- **A separate frontend service/deployment**: rejected on the same grounds as
  ADR-001 (modular monolith) — one more deployable, one more thing to keep in
  sync with the API's auth model, for a tool this small.
- **Always-on, no toggle**: rejected — the guide's own "What Not to Build"
  discipline (already cited by Architect's `claude.md` Key Focus) argues for
  restraint by default; a bank running this in production may legitimately
  not want an internal browsing tool reachable at all, and "one config value"
  is a cheap way to honor that without maintaining two build variants.

## Implementation
- Assets: `src/main/resources/webapp/ui/index.html`, `styles.css`, `app.js`.
- `UiResourceConfig` (`config/`): `@ConditionalOnProperty("app.ui.enabled")`,
  registers `/ui/**` → `classpath:/webapp/ui/`.
- `spring.web.resources.add-mappings: false` (`application.yml`): Boot's own
  `/webjars/**` and `/**` static mappings are unconditional and would expose
  anything dropped under the classpath regardless of this ADR's toggle — off,
  so `UiResourceConfig`/`DocsResourceConfig` (ADR-014) are the only paths in.
- `SecurityConfig`: `/ui/**` is `permitAll()` — the shell loads without a
  token; the dashboard's own `fetch` calls to `/v1/**` carry the pasted
  Bearer token and are authorized exactly as any other API caller.
- Disabling: `app.ui.enabled=false` means `UiResourceConfig` never registers
  a handler — `/ui/**` 404s via the existing `NoResourceFoundException`
  mapping (M-04), not a security-layer block that could be bypassed by a
  future refactor.

## Consequences
**Positive**
- Zero new authority, zero new stored credentials, zero new deployable.
- Genuinely optional: `app.ui.enabled=false` removes the dashboard's
  footprint from the running app entirely (no handler, no route).
- No build-toolchain dependency added to `Dockerfile`/CI.

**Negative**
- Vanilla JS without a component model will get harder to extend cleanly
  if the dashboard grows much past its current three tabs (Transactions /
  Summary / Sources) — a deliberate, documented tradeoff (see Alternatives),
  not an oversight.
- The "paste a token" gate is honest about today's auth model but is real
  friction for a non-technical user; that friction is inherited from the API
  having no session-based auth at all, not created by this ADR.
- No CI job exercises the dashboard's JS (no Playwright coverage of `/ui/**`
  yet) — `DocsAndUiAvailabilityTest` verifies the toggle behavior and that
  the shell is reachable, not the client-side behavior itself. Flagged, not
  silently assumed covered.
