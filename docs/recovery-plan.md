# Disaster Recovery Plan: Transact API

> **STATUS: DRAFT RUNBOOK — NOT EXECUTED.**
> This document describes *how* recovery would be performed and *what* would be measured. It is not evidence that recovery works.

## 1. Recovery Objectives — TARGETS ONLY

> ⚠️ **UNTESTED — no restore drill has been executed.**

The figures below are **design targets**, not verified capabilities. Per the Staff Engineer Guide (§96AD: *Backup Is Not Recovery*), no particular RPO/RTO may be claimed as "met" until restore drills have been designed, run, and measured. Until then:

- **RPO — Target: 15 minutes.** *Not verified.* We intend to lose no more than 15 minutes of ingested transaction data **if** continuous WAL archiving is configured and functioning — which has not been demonstrated.
- **RTO — Target: 1 hour.** *Not verified.* We intend to be fully operational within one hour of a declared disaster **if** the procedures below are followed successfully — which has never been rehearsed.

**Validation rule:** every item in §4 must remain unchecked (or be explicitly marked "not yet verified") until a real drill produces evidence. Do not tick boxes on the strength of a backup job succeeding.

## 2. Backup Strategy (designed, not drilled)

### 2.1 Database (PostgreSQL)
- **Full Backups (planned)**: Daily snapshots using `pg_dump` or cloud-native snapshots (e.g., AWS RDS Snapshots).
- **Continuous Archiving (planned)**: Write-Ahead Log (WAL) archiving to S3/Azure Blob Storage for Point-In-Time Recovery (PITR) — this is the mechanism the 15-minute RPO target depends on.
- **Verification (not done)**: Monthly restore tests into a staging environment have been **designed as a practice but never executed**. No restore test has produced a measured RPO/RTO.

### 2.2 Messaging (Kafka)
- **Topic Replication (planned)**: Replication factor of 3 across Availability Zones in a production cluster (the local Compose broker is single-node and provides no redundancy).
- **State Recovery**: Since ingestion is idempotent, the database is the source of truth. If Kafka data is lost, state can be rebuilt by re-running the ingestion scheduler from stored cursors (`source_sync_state`) — a property of the design, **not yet exercised in a drill**.

## 3. Recovery Procedures (runbook drafts)

These are **drafts for a future drill** — steps have not been timed or validated end-to-end.

### 3.1 Scenario: Database Corruption
1. **Stop Application**: Scale all API and Consumer pods to 0.
2. **Restore Snapshot**: Restore the latest daily snapshot.
3. **Apply WAL**: Replay WAL logs up to the point of failure (this step is what would satisfy the RPO target — untested).
4. **Verify Integrity**: Run data-consistency checks on `transactions` (row counts, `UNIQUE(source_id, source_transaction_id)` re-validation, aggregate spot-checks).
5. **Restart**: Scale application pods back up; confirm `/actuator/health` and per-source sync status.

### 3.2 Scenario: Region Outage — NOT CURRENTLY SUPPORTED
The deployment is **single-region**. There are no cross-region read-replicas and no infrastructure-as-code for a standby region today.

Future steps (none implemented):
1. **DNS Switch**: Update DNS to the standby region *(requires multi-region topology that does not exist yet)*.
2. **Restore Persistence**: Promote a cross-region read-replica *(replicas not provisioned)*.
3. **Provision Infra**: Rebuild load balancers/networking in the secondary region via **Terraform — not implemented; infrastructure provisioning is future work** (Helm covers only the application chart).
4. **Deploy App**: `helm upgrade --install` into the secondary cluster *(chart exists; secondary cluster does not)*.

### 3.3 Scenario: Kafka Loss
1. Broker data is reconstructable: reset consumer offsets if required.
2. Re-trigger ingestion; idempotent persistence (`UNIQUE(source_id, source_transaction_id)`) prevents duplicates while cursors drive incremental catch-up.
3. *Not exercised in any drill.*

## 4. Validation Checklist

> All items below are **NOT YET VERIFIED**. Keep unchecked until a restore drill produces evidence.

- [ ] Database restore from snapshot completes within the RTO target — **not yet verified**.
- [ ] WAL replay recovers data within the RPO target — **not yet verified**.
- [ ] Database connectivity verified post-restore — **not yet verified**.
- [ ] Kafka consumer group offsets restored or rebuilt — **not yet verified**.
- [ ] API `/actuator/health` returns `UP` after restore — **not yet verified**.
- [ ] Sample transaction query returns data from the last 15 minutes — **not yet verified**.
- [ ] Measured RPO and RTO recorded from a timed drill — **no drill has been executed**.

## 5. Exit Criteria for "Tested Recovery"

This plan may claim measured RPO/RTO only after:
1. A full restore drill is executed in a non-production environment.
2. Elapsed restore time and data-loss window are recorded.
3. Results meet (or revise) the targets above, and this document is updated with the drill date and measurements.

---
**Status**: Draft (runbook — untested)
**Owner**: Operations/SRE
**Date**: 2026-09-23
