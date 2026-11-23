# Java 21 build-tool completion

The user reaffirmed Java 21 before further BlueGenes work. All active Java roots
already require JDK 21 and use `--release 21`; current executable CI is green.
One observed gap remains: Checkstyle 7.8.1 rejects switch expressions. Upgrade
the shared style engine to 14.4.0, verified in Maven Central and the
[official release documentation](https://checkstyle.org/).

Preserve the existing style policy, zero-warning gate, exclusions, and XML/HTML
reports. Translate renamed/split checks rather than dropping them. Prefer the
standard Gradle Checkstyle task over the custom Checkstyle 7 CLI workaround if
modern source-only checks permit it. Preserve database contents. Application edits
are mechanical style fixes, except for blocking Aislop findings in touched files:
report interrupted waits and writer-close failures, with targeted regression tests.

Use a standalone Gradle fixture that compiles Java 21 records, pattern switches,
and text blocks, then checks them with the actual project configuration. A negative
fixture must still report missing documentation, unused imports, and whitespace
violations. Run all five configured build roots and the report parser. Fix real
new findings, document any unavoidable semantic difference, and do not add blanket
suppressions. Verify exact-source hosted checks before returning to BlueGenes.

The existing inline workflow and fork push authorization apply. Claude's Alliance
worktrees and the isolated unfinished frontend remain untouched during this work.
