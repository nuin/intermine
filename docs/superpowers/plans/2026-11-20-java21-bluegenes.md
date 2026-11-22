# Java 21 readiness implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build and run InterMine 6 and Alliance BlueGenes together on Java 21, removing InterMine's obsolete Jersey client.

**Architecture:** Keep InterMine's existing Java 21 floor. Add a shared BlueGenes build/runtime guard and backend test entry point; validate the compiled frontend against the current REST server. Replace the sole Jersey consumer with a bounded JDK HTTP registry adapter.

**Tech Stack:** Java 21, Gradle 8.14.7, Clojure/ClojureScript, Leiningen, Ring, PostgreSQL 18, Solr 10.

**Spec:** `docs/superpowers/specs/2026-11-20-java21-bluegenes-design.md`

## Global Constraints

- Java 21 or newer is required for the next major release.
- Preserve InterMine `/service` contracts and all existing runtime databases/lists.
- Edit BlueGenes only in `/private/tmp/intermine-bluegenes-java21`; preserve the supplied dirty checkout.
- No destructive `intermine_boot` setup, production deployment, or publication.
- Preserve Alliance tools and multi-mine behavior; upgrade dependencies only when verified builds require it.
- This plan is project 1 of the UI replacement program; continue with projects 2–5 afterward.

## Review Focus

- Unsupported JVMs must fail clearly at build and server entry points; test version parsing below and above 21.
- Registry input with non-ASCII, ampersands and slashes must remain query/path data; inspect fixture requests.
- Redirects, non-200 responses, malformed/trailing JSON and stalled registry calls must not leak resources or block indefinitely.
- A production bundle must load under `/bluegenes` with the local mine namespace, not merely compile.
- BlueGenes frontend/backend service roots must agree on identity and lists; verify real login/logout/list calls with disposable accounts.

## Task 1: BlueGenes Java 21 build and runtime

**Files (BlueGenes):** `script/bluegenes/java_runtime.clj`, `project.clj`, `src/clj/bluegenes/core.clj`, `test/clj/bluegenes/java_runtime_test.clj`, `.circleci/config.yml`, `docs/building.md`.

**Interfaces:** Produces `bluegenes.java-runtime/require-java21!` (zero arguments uses the JVM specification version; one string argument validates it). Produces `lein test-backend`, separate from browser-based `lein kaocha`.

- [x] Add tests accepting `21`/`25` and rejecting `1.8`/`17`/`20` with a clear Java 21 message; run and observe the missing implementation.
- [x] Implement the shared guard, load it at project evaluation and invoke it before server startup; add backend test paths/profile/alias.
- [x] Run `lein test-backend`; expected zero failures/errors. Run `lein cljfmt check`; expected success.
- [x] Make the BlueGenes CI JVM consistently 21 with explicit validation and backend checks; document clean `npm ci`, runtime floor and test commands.
- [x] Build from the committed npm lockfile with `npm ci` and `lein build`; expected fingerprinted JS/CSS artifacts. Fix observed build failures with regression coverage where behavior changes.
- [x] Commit the independently verified Java 21 changes in the isolated BlueGenes branch.

## Task 2: Retire the Jersey registry client

**Files (InterMine):** `intermine/webapp/src/main/java/org/intermine/web/registry/RegistryClient.java`, corresponding `RegistryClientTest.java`, `SemanticMarkupFormatter.java`, `RuntimeCompatibilityTest.java`, `intermine/webapp/build.gradle`, `config/gradle/runtime-dependencies.gradle`, `config/lib/verify_war.py`.

**Interfaces:** Produces `RegistryClient(URI registryRoot, Duration timeout)` and `Instance findInstance(String mineUrl) throws IOException, InterruptedException`. The formatter keeps its existing null-instance fallback and restores interrupt status if interrupted.

- [x] Add local HTTP fixture tests for two-step JSON lookup, UTF-8/query/path encoding, missing namespace/instance, error status, redirects, malformed/trailing JSON and request timeout. Run focused Gradle tests; expected missing implementation.
- [x] Implement finite connection/request timeouts, no redirects, explicit strict Jackson decoding, and Java 21 HTTP client closure. Do not store per-request base URLs in mutable static state.
- [x] Replace the formatter's Jersey calls, preserve cache/fallback, and replace the runtime JSON round-trip test with the JDK HTTP client.
- [x] Scan all consumers, remove unused Jersey/JAX-RS/HK2 dependencies, and make the WAR verifier reject retired package providers. Preserve unrelated injection consumers if the dependency scan finds any.
- [x] Run focused tests, core build/install and WAR verification; expected zero failures and no retired providers.
- [x] Commit the Jersey retirement and tests.

## Task 3: Verify the Java 21 pair

**Files:** BlueGenes `config/intermine6/config.edn`, `project.clj`, integration scripts/tests and documentation as required by observed behavior; InterMine modernization decision record.

**Interfaces:** Consumes both prior tasks; produces `lein intermine6`, a production-profile run against an explicitly configured InterMine 6 root and namespace under `/bluegenes`.

- [x] Add the local profile and operator instructions without embedding credentials or resetting databases.
- [x] Start the preserved InterMine runtime and the compiled BlueGenes server; check real metadata, login/logout, lists, templates and report navigation in the browser. Record scientific-data fixture requirements for subsequent UI parity work.
- [x] Run existing frontend/backend/tool suites and InterMine live/client/list-preservation gates; expected all applicable checks pass, with any pre-existing pending tests named.
- [x] Replace the legacy CI integration harness with a reproducible Java 21 / InterMine 6 fixture or a bounded explicitly configured runtime check; do not claim old bootstrap data proves InterMine 6 compatibility.
- [x] Run Aislop on both change ranges; fix errors and fixable warnings. Complete one fresh whole-project review and one Important/Critical fix pass with regression tests.
- [ ] Commit, push InterMine changes to the user's fork and record exact validation evidence. Prepare BlueGenes changes for an authorized fork destination. Continue to the authentication integration project.
