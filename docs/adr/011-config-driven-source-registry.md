# ADR 011: Config-Driven Source Registry

## Status
Accepted

Supersedes the *registration* aspect of ADR-006 (per-source adapters as hard-coded `@Component` beans discovered by the scheduler). ADR-006's boundary principle is unchanged and extended: heterogeneous payload shapes still die at the integration layer — normalization remains code-owned and happens before anything leaves the integration layer.

## Context
Until now the three sources were wired in code: `SourceA/B/CAdapter` as hard-coded `@Component` classes, each with its own DTOs and normalization embedded inside. That was right for three in-process mocks (ADR-006), but it couples "which sources exist" to the deployment artifact:

- **The source inventory varies per environment.** The default demo (and the E2E suite, which asserts three freshness sources) runs three MOCK sources; another environment runs KAFKA, S3, or HTTP feeds. Choosing sources must not mean choosing a code path.
- **Enable/disable is an operational lever, not a code change.** Quarantining a misbehaving feed for an hour must not require a rebuild and redeploy.
- **Freshness metadata needs the same list the pipeline uses.** ADR-008 made `completeness`/`freshness` a function of *configured* sources; "configured" must have exactly one definition, or the API and the pipeline can disagree about reality.
- Meanwhile the transports are now genuinely diverse — mock files, external Kafka topics, S3-compatible object stores, HTTP APIs — while payload semantics stay exactly as ADR-006 described them: per-source, quarantining, and unit-tested in Java.

The design question is where to draw the config/code line.

## Decision
**The registry is config; the semantics are code.** A single list — `app.sources.registry` in `application.yml` — defines every source (transport, identity, operational state), and both the ingestion pipeline and the freshness/completeness metadata read exactly that list. Each entry is a `SourceDescriptor`:

1. **Descriptor contract.** Each entry carries `id` (stable identity — equals `source_id` on transactions, `source_sync_state`, and freshness entries), `name` (display on `/v1/admin/sources`), `enabled`, `type` (`MOCK` | `KAFKA` | `S3` | `HTTP`), `normalizer` (a code-owned strategy key, e.g. `source-a-v1`), and the nested per-type config block. One example per type, exactly as bound:

   **MOCK** — raw records from a classpath JSON file:
   ```yaml
   - id: SOURCE_A
     name: "Source A (bank feed)"
     enabled: true
     type: MOCK
     normalizer: source-a-v1
     mock:
       data-location: classpath:mock/source-a.json
   ```

   **KAFKA** — polls an external topic:
   ```yaml
   - id: SOURCE_LEDGER
     name: "Partner ledger feed"
     enabled: true
     type: KAFKA
     normalizer: source-b-v1
     kafka:
       bootstrap-servers: kafka:29092
       topic: partner.ledger.raw
       group-id: transact-source-ledger
       auto-offset-reset: earliest    # first fetch only
       poll-timeout-ms: 2000
       max-poll-records: 500
   ```

   **S3** — lists objects under a prefix (MinIO or AWS via endpoint override):
   ```yaml
   - id: SOURCE_D
     name: "Source D (S3/MinIO file drop)"
     enabled: true
     type: S3
     normalizer: source-c-v1         # transport ≠ payload shape
     s3:
       endpoint: http://minio:9000
       region: us-east-1
       bucket: transact-sources
       prefix: source-d/
       access-key: minioadmin
       secret-key: minioadmin
       max-keys-per-fetch: 10
   ```

   **HTTP** — `GET base-url` + `path` with the cursor as a query param:
   ```yaml
   - id: SOURCE_E
     name: "Source E (partner API)"
     enabled: true
     type: HTTP
     normalizer: source-a-v1
     http:
       base-url: https://partner.example.com
       path: /v1/transactions
       cursor-param: cursor          # opaque, passed through verbatim
       connect-timeout: 5s
       read-timeout: 10s
   ```

2. **Cursor semantics** (persisted per source in `source_sync_state`, as in ADR-006):
   - `MOCK`: the cursor stores the **`MOCK_EXHAUSTED` marker** once the file is fully consumed; subsequent cycles short-circuit without re-ingesting (duplicates would be rejected by the unique constraint anyway — ADR-005).
   - `KAFKA`: offsets are tracked in the cursor as a **`"partition:offset,partition:offset"` CSV**, so the pipeline is at-least-once; `auto-offset-reset` applies **only to the very first fetch**, when no cursor exists yet — afterwards the persisted cursor always wins.
   - `S3`: objects under `prefix` are listed lexicographically; the cursor is the **last consumed object key**, and the next fetch lists with `startAfter` = cursor.
   - `HTTP`: the cursor is an **opaque query parameter** (`cursor-param`), passed through verbatim; the source answers the contract **`{"records": [...], "nextCursor": "..."}`** and the returned `nextCursor` becomes the new cursor.

3. **Normalization stays in code.** `SourceNormalizer` strategies (in `infrastructure/integration/normalizer`) are keyed by name; the registry references the key. A new payload shape means a new strategy — versioned key, quarantine behaviour, unit tests — plus one registry entry; never a YAML mapping language. The S3 demo entry proves the split: `SOURCE_D` is a different transport reusing `source-c-v1`, so payload semantics are untouched by the transport change.

4. **A registered-but-disabled source is never hidden.** `enabled: false` removes a source from the ingestion cycle but *not* from the metadata: it keeps appearing in `freshness` as **`UNKNOWN`** and forces `completeness = PARTIAL` (the ADR-008 rules are derived from the same registry). The system never lies about data completeness — a feed that exists but is not syncing is disclosed, not dropped. Deleting the entry entirely is the honest way to retire a source.

5. **Fail-fast at startup.** The registry is validated at boot: an unknown `normalizer` key, a duplicate `id`, or a missing config block for the declared `type` aborts startup with a precise message. Misconfiguration must surface on deploy, not as a mystery `FAILED` cycle later.

## Alternatives Considered
- **Declarative field-mapping DSL in YAML** (the registry also owns the payload mapping — `amount: $.value`, sign conventions, date formats): rejected — harder to debug than Java (errors surface as a generic interpreter failure, not a stack trace into the mapping); cannot express the quarantine/ambiguity rules that make normalization safe (missing/invalid fields surfacing as nulls, per-field sign conventions); loses compile-time type safety; and discards the normalizer unit tests that pin each shape's semantics today. The DSL would turn YAML into a programming language without a language's tooling.
- **Fully code-driven adapters** (the status quo — hard-coded `@Component` classes, transport and registration in code): rejected — "which sources exist" varies per environment (the default demo's three MOCKs vs a partner environment's real feeds) and needs an operational `enabled`/`disabled` lever without a rebuild; the inventory is invisible to git review; and freshness needs the same list at request time, so bean discovery is the wrong source of truth.
- **Registry in the database with an admin API** (add sources at runtime, no restart): rejected for now — invents an auth surface and a migration story to avoid one restart, makes the source inventory invisible to code review, and reintroduces exactly the between-environment drift that committed config exists to prevent. Runtime registration is Future Evolution if adding sources ever becomes more frequent than deploying.

## Implementation
- `SourceDescriptor` / `SourceRegistryProperties` records in `config`, bound via `@ConfigurationProperties("app.sources")`, with startup validation per Decision §5.
- Transport adapters in `infrastructure/integration/adapter` — `MockTransactionSource`, `KafkaTransactionSource`, `S3TransactionSource`, `HttpTransactionSource` — all implementing the unchanged `TransactionSource` port (ADR-006), selected per descriptor.
- `SourceNormalizer` strategies in `infrastructure/integration/normalizer` (keys `source-a-v1`, `source-b-v1`, `source-c-v1`).
- Default registry: three MOCK sources in `application.yml`; the `s3demo` profile (`application-s3demo.yml`) restates them and adds `SOURCE_D` (S3 → MinIO).

## Consequences
**Positive**
- Adding, enabling, or disabling a source is a reviewed config change, per environment — the `s3demo` profile adds a working S3 source with zero code changes.
- Pipeline and freshness metadata read one list — the API cannot report a source the pipeline does not know, and cannot hide one it does.
- Fail-fast startup validation (unknown normalizer key, duplicate id, missing type config) turns misconfiguration into a deploy-time error with a precise message.
- Transports compose with semantics: the same normalizer serves a MOCK file and an S3 drop of the same shape; a new transport never touches payload code.

**Negative / accepted costs**
- Spring profile files **replace** list properties rather than merging — a profile touching `app.sources.registry` must re-declare the whole registry (`application-s3demo.yml` restates the three MOCK sources; the file says so loudly). Omit one and the source vanishes from pipeline *and* metadata.
- Two places to look when a source misbehaves: the YAML descriptor (transport/enablement) and the normalizer class (semantics) — mitigated by the `normalizer` key living in the same YAML block and by sync-state errors naming the source id.
- A registered-but-disabled source reports `UNKNOWN`/`PARTIAL` forever, by design; consumers must actually read the metadata (ADR-008's standing caveat), and operators must delete entries for sources that are truly gone.
- Structural mistakes fail fast, but semantic ones (wrong bucket, wrong topic, unreachable endpoint) surface only on the first sync — `/v1/admin/sources` and the sync metrics are the detection path.
