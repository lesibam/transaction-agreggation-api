#!/usr/bin/env node
/**
 * Asserts a Playwright JSON report represents a genuinely executed, fully
 * passing run: at least one test ran, zero failures, zero skipped.
 *
 * Rationale: tests/e2e/global-setup.ts marks the suite skippable when the API
 * is unreachable, and Playwright exits 0 for an all-skipped run. A skipped E2E
 * run is "NOT RUN" and must never read as green — see
 * docs/principal_engineer_review_report.md (C-01) and the incident post-mortem.
 *
 * Usage: node scripts/e2e-assert.mjs <playwright-json-report>
 * Exit codes: 0 = fully executed and passed; 1 = empty/failed/skipped run;
 *            2 = usage error.
 */
import { readFileSync } from 'node:fs';

const file = process.argv[2];
if (!file) {
  console.error('usage: node scripts/e2e-assert.mjs <playwright-json-report>');
  process.exit(2);
}

const raw = readFileSync(file, 'utf8');
const start = raw.indexOf('{');
const end = raw.lastIndexOf('}');
if (start < 0 || end <= start) {
  console.error(`no JSON object found in ${file} — did Playwright run, or did it crash before reporting?`);
  process.exit(1);
}

const report = JSON.parse(raw.slice(start, end + 1));
const stats = report.stats ?? {};
const expected = stats.expected ?? 0;
const unexpected = stats.unexpected ?? 0;
const flaky = stats.flaky ?? 0;
const skipped = stats.skipped ?? 0;
const executed = expected + unexpected + flaky + skipped;

const problems = [];
const walk = (suites, path) => {
  for (const suite of suites ?? []) {
    const p = [...path, suite.title].filter(Boolean);
    for (const spec of suite.specs ?? []) {
      const title = [...p, spec.title].join(' > ');
      const results = (spec.tests ?? []).flatMap((t) => t.results ?? []);
      if (results.some((r) => r.status === 'skipped')) {
        problems.push(`SKIPPED: ${title}`);
      } else if (!spec.ok) {
        problems.push(`FAILED:  ${title}`);
      }
    }
    walk(suite.suites, p);
  }
};
walk(report.suites, []);

console.log(`E2E report: executed=${executed} passed=${expected} failed=${unexpected} flaky=${flaky} skipped=${skipped}`);
for (const problem of problems) {
  console.error(problem);
}

if (executed === 0) {
  console.error('E2E: no tests executed — refusing to pass an empty run');
  process.exit(1);
}
if (unexpected > 0) {
  console.error('E2E: failing tests present');
  process.exit(1);
}
if (skipped > 0) {
  console.error('E2E: skipped tests present — a skipped E2E run is NOT RUN, never green');
  process.exit(1);
}
console.log('E2E: full run verified — every spec executed and passed');
