# PR draft

## Summary

Reduce request-local timetable allocation in `TripPatternForDates` by retaining the existing
immutable `TripTimes` objects and service-day offsets instead of copying one arrival and departure
array per stop/trip combination.

## Issue

`TripPatternForDates` currently materializes two `stops × trips` integer arrays for every routing
request. This duplicates values already held by the timetable snapshot and creates avoidable
short-lived allocation pressure on large transit datasets.

The change keeps the existing merge and global trip-index order. For each sorted index it stores the
source `TripTimes` reference and its service-day offset. The arrival/departure accessors add that
offset to the corresponding source value. Frequency search, boarding/alighting checks, wheelchair
metadata, and the Raptor timetable API are unchanged.

The references are safe under OTP's existing realtime model: scheduled and realtime `TripTimes`
implementations are read-only after construction, realtime updates create replacement instances,
and copy-on-write publication replaces affected timetable/pattern objects in a new transit-data
snapshot. Requests retaining an older snapshot therefore continue to observe the old values.

This branch is based on official `opentripplanner/OpenTripPlanner` `dev-2.x` commit
`a0b62f69fc543e84687752e17dae8a66b01c5e28`. It contains only the H production change and its
regression tests; F, B/C, E2, graph, and performance-tool changes are intentionally excluded.

## Unit tests

- Added public-API regression coverage for multiple stops/trips, positive and negative offsets,
  overnight trips whose global order overlaps a following service day, dwell times, and realtime
  snapshot isolation.
- `TripPatternForDatesTest`: 6 tests passed.
- Five directly related timetable/request test classes: 80 tests passed.
- Full application reactor: 8,255 tests passed, 0 failures/errors, 20 skipped.
- Spotless and Checkstyle passed; `git diff --check` passed.
### Historical separate-graph run (superseded for performance)

- A shaded JAR was built successfully. A host-side local equivalent of the official Helsinki
  SpeedTest (`-p md -n 4 -i 3 -0`, Java 26.0.2.1, `-Xmx8g`) completed for A and H. The Maven exec
  plugin was unavailable locally, so this is not an official CI run. Both variants reported
  non-zero expected-result mismatches, and their separately built graphs differed by 15 edges;
  therefore the performance result is `INCONCLUSIVE` and no latency/RSS/allocation improvement is
  claimed here. Detailed hashes and logs are in `H-ONLY-RESULT.md`.

### Current shared-graph evidence

The follow-up six-pair matrix uses one serialized graph (SHA-256
`178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0`) for all 12 fresh JVMs.
Every run exits 0 and emits the same 1,207-row CSV (SHA-256
`f417493103bba636a2ab69f06c1042e7cadf08b0ebc9498334d5b0ff96f41b43`); all 408 cases match
between A and H including c1/c2 costs. Both variants match 233/408 entries in the checked-in
official expected CSV, so the 175 residual mismatches are not reported as H-only failures.

The primary Routing Total mean is 93.42 ms for A and 94.63 ms for H (paired improvement
−1.35%, bootstrap 95% CI −3.58% to +1.03%); sampled whole-process peak RSS is 5,220,544 KiB
versus 5,250,784 KiB (−0.99%, CI −7.43% to +3.67%). Separate JFR recordings estimate 11.37%
lower whole-recording allocation pressure for H and 83.84% lower `int[]` pressure. These are
local Java 26 host results, not upstream CI, and support an allocation-pressure claim only;
they do not support a latency or RSS reduction claim.

## Documentation

The production fields document that they retain source trip times and per-trip service-day offsets.
The accompanying issue draft records the snapshot rationale and the accessor-cost/locality trade-
off for maintainer review. No configuration option or serialization format changed.

## Changelog

Reduce timetable allocation in `TripPatternForDates` while preserving all tested routing
results. Local shared-graph evidence supports lower allocation pressure; latency and RSS wins
are not claimed.

## Bumping the serialization version id

Not applicable. This change affects request-local routing data and does not alter the serialized
routing graph.
