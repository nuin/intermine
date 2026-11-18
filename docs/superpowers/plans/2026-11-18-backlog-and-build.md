# Backlog Triage and Build Modernization Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Triage every open core issue, then make the existing build usable with Gradle 8.14.7 while preserving Java 8 application compatibility.

**Architecture:** Keep the existing module layout, generated model machinery and test suites. Migrate obsolete dependency configurations, publishing APIs and task properties; preserve published artifact names and local install behavior. Run verification in disposable Docker infrastructure because the host has no Java runtime.

**Tech Stack:** Java 8, Groovy, Gradle 8.14.7, Python 3.10+, PostgreSQL-backed existing tests.

**Spec:** [Maintenance assessment](../../maintenance-assessment-2026-11-18.md), narrowed by the user's instruction to backlog triage followed by build modernization.

## Global Constraints

- Complete first-pass triage before changing build code; no upstream issue mutations.
- Retain Java 8 source/target compatibility and existing artifact coordinates.
- Do not change database versions, production libraries, public APIs or the UI as part of this build migration.
- Use the Gradle 8.14.7 distribution and official checksum; preserve explicit custom/offline distribution support.
- Exercise database setup only in disposable infrastructure.
- Do not publish artifacts remotely or alter aislop rules.

## Review Focus

- Generated model and test-resource artifacts must remain available to downstream modules.
- Runtime Ant tasks need runtime dependencies even after declaration configurations stop being resolvable.
- WAR/classifier artifacts and consumer dependency scopes must survive publishing migration.
- Test failures must be visible; a successful compile is not a passing integration suite.
- Offline/custom repository and distribution settings must remain usable.

### Task 1: Backlog triage

Files: `docs/maintenance-triage-2026-11-18.json` and matching Markdown summary.

- [x] Read all 218 issue bodies; inspect selected high-impact source paths.
- [x] Assign component, disposition, impact priority, rationale, next step and evidence.
- [x] Validate complete ID coverage, evidence paths and summary counts; review the actionable queue.

### Task 2: Establish the build baseline

Files: temporary baseline checkout/logs under `/private/tmp`.

- [x] Configure the original build and run the plugin plus representative core build in an isolated Java 8 environment.
- [x] Record pre-existing dependency or infrastructure failures and distinguish them from migration errors.

### Task 3: Migrate Gradle APIs

Files: module `build.gradle` files, `plugin/src/main/groovy`, wrapper templates/binaries and `config/lib/install_intermine.py` as required by verified incompatibilities.

- [x] Replace legacy `maven` publishing with `maven-publish`, preserving local `install` usage and artifact metadata.
- [x] Migrate dependency declarations to `api`/`implementation`, test and runtime variants; use resolvable classpaths for execution.
- [x] Replace removed task/report/archive/repository APIs and repair task dependencies revealed by current Gradle validation.
- [x] Update generated wrapper configuration and wrapper files; validate distribution integrity.
- [x] Build the plugin, all core modules and biological modules; inspect publication artifacts and a downstream mine.

### Task 4: Verification and handoff

Files: CI workflow/build documentation changes if required by the verified new build.

- [x] Run appropriate existing tests and Checkstyle, recording exact scope and any independent baseline failures.
- [x] Run `aislop scan --changes --json` and address actionable findings.
- [x] Review final diff and document commands, supported toolchain and outstanding work without claiming unrun checks passed.

Final results and remaining validation boundaries are recorded in [the build ledger](../../build-modernization-2026-11-18.md). The standard installer passed through all five build roots after the module checks.
