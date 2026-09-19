# H-only contribution preparation

Follow-up status (2026-09-15): `H-ONLY-SHARED-GRAPH-AGENT-PLAN.md` S0–S5 has now been
executed. The same serialized graph was used for all A/H runs. The result is semantic
equivalence plus lower sampled allocation pressure, but no demonstrated RSS or latency win.
In the logs below, "successful searches" counts expected-result matches, not simply completed
routing requests. Full current evidence is in `H-ONLY-SHARED-GRAPH-RESULT.md` and
`H-ONLY-SHARED-GRAPH-RUN-LEDGER.md`.
The historical reactor count of 8,255 includes 20 skipped tests.

Updated 2026-09-15. This report covers only the H change; it does not reuse the historical H+F
commit or its performance measurements.

## Baseline and isolation

- Repository: `/Users/wa/golbob/개인/OpenTripPlanner`
- Worktree: `/private/tmp/otp-h-only-run-v83UVX`
- Branch: `codex/otp-h-trip-times`
- Base: official `opentripplanner/OpenTripPlanner` `dev-2.x` at
  `a0b62f69fc543e84687752e17dae8a66b01c5e28`
- Historical mixed commit `8dbad55ba40a36606e47f98688db958b4c3eeab0` was inspected but not
  cherry-picked.
- The uncommitted H-only patch SHA-256 is
  `ca1caaf61758100a821211c716e754a597936d182c7f63c6e6630592f29fa9a1`.

The only changed files are:

1. `application/src/main/java/org/opentripplanner/routing/algorithm/raptoradapter/transit/request/TripPatternForDates.java`
2. `application/src/test/java/org/opentripplanner/routing/algorithm/raptoradapter/transit/request/TripPatternForDatesTest.java`

There are no F (`DestinationArrivalPaths`, `PathConfig`, `McRangeRaptorConfig`), B/C, E2, graph,
search-window, or measurement-tool changes in this worktree.

## Implementation

`TripPatternForDates` no longer allocates request-local `stops × trips` arrival and departure
arrays. During the existing global trip merge it stores:

- one `TripTimes` reference for each globally sorted trip index; and
- the service-day offset for that index.

`arrivalTime` and `departureTime` read the corresponding source `TripTimes` value and add the
stored offset. Trip ordering, accessibility metadata, frequency search, and the public timetable
accessors remain unchanged.

The H tests exercise multiple stops and trips, an overnight trip whose effective order overlaps a
following service day, positive and negative offsets, dwell (arrival/departure distinction), and a
realtime update published as a new snapshot while an older request retains its original values.
The tests observe the public timetable API; they do not inspect removed fields with reflection.

## Snapshot safety

The reference lifetime was checked against the current implementation rather than inferred from a
class name:

- `TripTimes` is sealed. `ScheduledTripTimes` is a final read-only implementation, while
  `RealTimeTripTimes` is final and keeps its state in final fields.
- `RealTimeTripTimesBuilder` produces a new `RealTimeTripTimes` for an update; it does not mutate a
  published instance.
- `Timetable` publishes immutable trip-time lists and indexes. `TimetableUpdateMapper` builds a
  copied `RaptorTransitData` and replaces whole `TripPatternForDate` instances when an update is
  applied.
- The updater concurrency documentation describes copy-on-write publication: a routing request
  sees an unchanging snapshot, and an older snapshot remains valid while a new one is published.

Therefore a request retaining an H `TripPatternForDates` keeps reading the `TripTimes` objects from
the snapshot it started with. A later realtime update reaches a new `TripPatternForDates` through a
new snapshot. No mutable `TripTimes` instance is exposed by this change.

## Verification

Environment: macOS 26.5.2, Apple M3 Pro (11 cores), 18 GiB RAM, Maven 3.9.16 running Java
26.0.2.1, compiler release 25. The standalone `/usr/bin/java` reports 23.0.1; Maven's Java version
above is the runtime used for the build and tests.

- H target: `mvn -q -pl application -Dtest=TripPatternForDatesTest test` — 6 tests, 0 failures,
  0 errors, 0 skipped.
- Direct related tests (five timetable/request test classes) — 80 tests, 0 failures, 0 errors,
  0 skipped.
- Reactor impact tests: `mvn -q -pl application -am test` — 8,255 tests, 0 failures, 0 errors,
  20 skipped.
- Formatter/checkstyle: `mvn -q -pl application spotless:check checkstyle:check` — pass.
- Shaded build: clean Maven package with the linked-worktree git-metadata workaround
  (`-Dmaven.gitcommitid.skip=true`, explicit upstream SHA/branch properties, and
  `-Dplugin.spotless.skip=true`) — pass. The resulting
  `otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar` SHA-256 is
  `e0b57b2ed105db13fa90f32a49abb26cc2b0cabca41796d88cf88a51f4c29ab9`.
- `git diff --check` — pass.

## Performance status and limits

### Helsinki local equivalent

The official February 2026 Helsinki inputs were downloaded into an isolated temporary directory;
the checked-in remote build config was preserved as `build-config-remote.json`, while the local
experiment config only changed the two source paths to the downloaded files. Both config files were
validated with `jq`. Input hashes are OSM
`6eaaff04885056857824f953e723404d4c3ff86811c528b8255a1911c198b377` and GTFS
`af4b8e9fe36e2014d7272378653a127f06084aa2907ee5deccdd71afda15b479`; local build config hash is
`6078179077d609ce06bb588c5a272202a626091df80c94b3d768e1450bf0bc66` and the unchanged remote
config hash is `e21ecd198cfaae0426ab09f31c89d6078721cc9ba1d38e8716c927201e0899a7`.

The graph was built on the host with Java 26.0.2.1, `-Xmx8g`,
`-XX:+UseCompactObjectHeaders`, and `caffeinate -dimsu`; no Docker container or application
service was started or stopped. H completed in 127.69 s with `|V|=813,280`, `|E|=2,236,006`,
8,302 stops, 1,007 patterns, and 4 constrained transfers. The H graph hash is
`e1cce9681eb20861a0f7e53a24d19028a124dd86e204f5be26a5b7217aeb3871`.

The official `SpeedTest` main was run with the same `-p md -n 4 -i 3 -0` arguments. The Maven
`exec` goal was unavailable in the local dependency cache, so this was a direct-Java equivalent
using the application test classes, the H shaded JAR, and the resolved Maven dependency classpath;
it is not an official CI run. H completed with exit 0 in 171.98 s. Each of the four samples ran 408
cases, with 227 successful searches and 181 `UNEXPECTED RESULTS`; worker mean was 56.0 ms and
total mean 98.8 ms. Raw log:
`/private/tmp/otp-h-only-helsinki-bjMNbI/speedtest-h-xmx8g.log`; result CSV SHA-256:
`5b025c6eadedbb3b59f8715ea767ec9541f1f2db663d3dc0f584e0d413b67f90`.

For a same-input A reference, the upstream SHA was built with the same host flags and completed in
127.75 s (`|V|=813,280`, `|E|=2,236,021`, same transit counts). A completed its local equivalent in
169.88 s; each sample had 233 successful searches and 175 `UNEXPECTED RESULTS`, worker mean 53.8
ms and total mean 97.3 ms. A graph hash is
`178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0`, JAR hash is
`772ff39a2ef4f8551afab1b053b548742fadeeacab55c6fd4c905b78419d068f`, and result CSV hash is
`f417493103bba636a2ab69f06c1042e7cadf08b0ebc9498334d5b0ff96f41b43`.

Both A and H differ from the checked-in expected CSV, so the non-zero `UNEXPECTED RESULTS` counts
are recorded rather than hidden. The separately built graphs differ by 15 street edges despite
identical input/config, and the output CSVs also differ. Consequently this run demonstrates that
both variants build and execute on the official fixture, but it does not isolate an H performance
effect or prove output equivalence; the performance conclusion is `INCONCLUSIVE`. Historical Seoul
H+F/HF measurements remain excluded from H-only evidence.

Next work is Phase 4/5 review and maintainer-facing draft refinement. A future controlled A/H
benchmark should use a shared serialized graph (after explicit compatibility validation) or an
otherwise deterministic graph build, plus the official Maven runner if its plugin dependency is
available. No claim of a latency, RSS, or allocation improvement is made here.

## Phase 4/5 handoff

The historical Seoul ablation, counter, JFR, and PR-evidence documents were reviewed. Their
H+F/HF results, reduced-feed experiments, and sampled allocation figures are retained as historical
context only and are not cited as H-only Helsinki performance evidence. The English Issue and PR
drafts are updated to describe the local-equivalent run and its limitations. No commit, issue, or
pull request has been created.

## Current shared-graph result

The formal matrix used A/H/A/H/A/H ordering across six fresh-JVM pairs, with one shared graph
(`178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0`), Java 26.0.2.1,
`-Xmx8g`, and the official local SpeedTest arguments `-p md -n 4 -i 3 -0`. All 12 runs exited
0. Each output CSV had 1,207 rows, the same SHA-256
`f417493103bba636a2ab69f06c1042e7cadf08b0ebc9498334d5b0ff96f41b43`, and an independent
field-by-field comparison found no result or cost difference across all 408 cases.

The six-pair Routing Total means were 93.42 ms (A) and 94.63 ms (H), paired change −1.35%
(10,000-resample paired bootstrap CI −3.58% to +1.03% for improvement). Sampled whole-process
peak RSS was 5,220,544 KiB (A) and 5,250,784 KiB (H), paired change −0.99% (CI −7.43% to
+3.67%). Swapins and Swapouts remained zero. H's Worker/RAPTOR mean was slower in all six
pairs (51.29 ms vs 55.17 ms; −7.63%, CI −10.33% to −4.92%).

Separate two-pair JFR recordings estimated whole-recording allocation pressure at 170.022 GB
per A recording versus 150.691 GB per H recording (−11.37% sampling estimate). `int[]`
weighted allocation fell from 22.326 GB to 3.608 GB per recording (−83.84%). These are
allocation-pressure diagnostics, not retained heap or RSS measurements; after-GC live heap
medians were 949.8 MiB (A) and 945.1 MiB (H). The appropriate PR wording is therefore
`ALLOCATION_REDUCTION_ONLY`: results/costs are equal, allocation pressure is lower, but this
local run does not establish a process-memory or speed improvement.
