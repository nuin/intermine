# Backlog reliability fixes

Work follows the approved sequence: three backlog defects, release validation and integration, then a runtime dependency audit. The working branch starts from Java 21 migration commit `5ae8ade9e0`.

## Region pagination — #2451

The AJAX endpoint accepts inclusive indices. A page ending exactly at the region count sent an out-of-range final index. A separate strict comparison prevented an already-complete page loading while the overall search was still running. Both comparisons now include equality.

The Node test runs the production browser script, replacing only DOM, timers and network boundaries. Before the fix, four of eleven cases failed; all eleven pass after it. CI runs the same command on Node 22:

```sh
node --test bio/webapp/src/test/js/*.test.cjs
```

The quality scan prompted mechanical cleanup of accidental global assignments, an unused assignment and identical navigation rendering. The remaining scan findings are:

- 79 `no-undef` reports for jQuery and six variables supplied by `genomicRegionSearchResultsBase.jsp`. These are false positives from scanning the script without its hosting page.
- Six `no-unused-vars` reports for functions called from JSP or generated HTML handlers, also false positives.
- One nonfixable warning for the existing hardcoded experimental JBrowse URL. Configuring that integration is outside this pagination fix.

No scanner rules or configuration were changed. The scan score is 92; there are no remaining confirmed errors or fixable warnings for this fix.
