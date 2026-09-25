# ADR 014: Swagger UI Viewer for the Hand-Maintained OpenAPI Contract

## Status
Accepted

## Context
`openapi.yaml` at the repo root is already the committed, authoritative API
contract (README, `docs/04-api-contract.md`) — a **deliberate, documented
choice to hand-maintain it rather than generate it from annotations**
(README: *"no springdoc annotation layer"*). Nobody can browse it as
anything but raw YAML today, though: no rendered, interactive view exists
in the running application. `springdoc-openapi` is the obvious dependency
most Spring Boot projects reach for here — but its default and most common
mode *generates* the spec by scanning `@RestController`/`@Operation`
annotations, which would create exactly the second source of truth the
hand-maintained-contract decision exists to avoid, and the drift risk
`docs/principal_engineer_review_report.md` M-05 already tracks as open.

## Decision
Serve `openapi.yaml` itself, unmodified, plus a `swagger-ui` viewer pointed
at it — **no annotation scanning, no generated spec**. Gated behind
`app.docs.enabled` (default `true`), independent of `app.ui.enabled`
(ADR-013): a deployment may want API docs reachable for integrators with the
internal dashboard switched off, or the reverse.

1. **One spec, one viewer.** `openapi.yaml` is copied byte-for-byte into the
   build (`pom.xml` `<resources>`, from the repo root — not hand-duplicated)
   and served at `/docs/openapi.yaml`. The viewer at `/docs/` loads exactly
   that file. There is no second spec anywhere for the two to disagree about.
2. **`org.webjars:swagger-ui`, not `springdoc-openapi`.** The webjar is
   static assets only (JS/CSS bundle) — a browser, not a generator. It never
   scans the classpath for `@RestController`s and cannot produce a spec that
   disagrees with the hand-maintained one, because it never produces a spec
   at all.
3. **Not served via Boot's automatic `/webjars/**` mapping.** That mapping is
   unconditional (see ADR-013's `spring.web.resources.add-mappings: false`)
   and would expose the swagger-ui assets regardless of `app.docs.enabled`.
   `DocsResourceConfig` serves them itself at `/docs-assets/**`, conditional
   on the same property as `/docs/**` — the toggle is real, not cosmetic.

## Alternatives Considered
- **`springdoc-openapi-starter-webmvc-ui` (annotation-generated spec)**:
  rejected — reopens the exact question the original ADR-level "no springdoc"
  choice already closed, and turns one hand-maintained contract into two
  specs (generated + hand-written) that can silently disagree. If this
  project ever moves to *generating* the contract instead of hand-maintaining
  it, that's a bigger decision than "add a viewer" and deserves its own ADR.
- **`springdoc-openapi`, configured to serve only the static
  `openapi.yaml`** (springdoc supports pointing `springdoc.swagger-ui.url`
  at an external file and disabling `/v3/api-docs` generation): rejected as
  unnecessary weight — it pulls in springdoc's full auto-configuration
  surface (and its own opinions about the `/v3/api-docs` path, actuator
  integration, etc.) to reach the same end state `org.webjars:swagger-ui`
  reaches directly, with a dependency whose primary purpose (annotation
  scanning) this project isn't using.
- **Redoc instead of swagger-ui**: viable alternative renderer, no
  functional difference for this use case (read + try-it-out); swagger-ui
  chosen only because it's the more widely recognized default, not for a
  technical reason — revisit if a Redoc-specific need appears.
- **No rendered viewer, YAML only**: rejected — `curl openapi.yaml | less` is
  a real degradation from what every other committed doc in this repo gets
  (rendered Markdown, at minimum); a five-minute `swagger-ui` wire-up is
  cheap relative to that gap.

## Implementation
- `pom.xml`: `org.webjars:swagger-ui:${swagger-ui.version}` dependency; an
  additional `<resource>` copying root `openapi.yaml` to `webapp/docs/` in
  the build output (`target/classes`), alongside the existing
  `src/main/resources` resource.
- `webapp/docs/index.html`: a small custom page (not the webjar's stock
  index, which defaults to the Petstore demo) pointing `SwaggerUIBundle` at
  `/docs/openapi.yaml`.
- `DocsResourceConfig` (`config/`): `@ConditionalOnProperty("app.docs.enabled")`,
  registers `/docs/**` → `classpath:/webapp/docs/` and `/docs-assets/**` →
  the webjar's classpath root (version-agnostic mapping; `index.html` carries
  the exact version string, which must stay in sync with `pom.xml`'s
  `swagger-ui.version` property — a version bump that misses `index.html`
  fails loudly, a 404 on the JS/CSS, not silently).
- `SecurityConfig`: `/docs/**` and `/docs-assets/**` are `permitAll()` — the
  spec and viewer are the same visibility as any other committed
  documentation; nothing behind them requires the caller to already have a
  token (unlike `/ui/**`'s data calls, this page has nothing to authorize).

## Consequences
**Positive**
- Zero risk of a second, disagreeing spec — the thing M-05 already flags as
  the real risk in a hand-maintained contract never gets a second source.
- Genuinely optional: `app.docs.enabled=false` removes both `/docs/**` and
  `/docs-assets/**` from the running app — no handler, not just a hidden
  link.
- No annotation-scanning dependency added to the classpath; `springdoc` is
  not on it.

**Negative**
- No automatic spec-vs-implementation drift detection — this ADR doesn't
  change that M-05 (`docs/principal_engineer_review_report.md`) is still
  open; a viewer makes the hand-maintained contract *visible*, not
  *verified*. The drift test M-05 recommends is still the right fix for
  verification, independent of this ADR.
- The webjar version is duplicated between `pom.xml` and `index.html`
  (Implementation, above) rather than resolved dynamically — a deliberate,
  documented tradeoff to avoid Maven resource-filtering machinery (which
  collides badly with `application.yml`'s own `${...}` Spring placeholder
  syntax) for one version string; `DocsAndUiAvailabilityTest` catches a
  mismatch.
