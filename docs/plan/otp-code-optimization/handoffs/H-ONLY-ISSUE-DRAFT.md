# Issue draft

## Title

Reduce timetable allocation in `TripPatternForDates`

## Proposal

`TripPatternForDates` currently creates two request-local arrays with one arrival and one departure
integer for every stop/trip combination. On large date ranges this duplicates timetable data for
every routing request and creates substantial short-lived allocation pressure.

This proposal keeps the existing global trip ordering but stores one `TripTimes` reference and one
service-day offset for each sorted trip index. The existing `arrivalTime(stop, trip)` and
`departureTime(stop, trip)` accessors then read the source value and add the offset on demand. No
Raptor search API, frequency-search contract, filtering rule, or graph serialization format is
changed.

The design relies on OTP's existing snapshot contract. `ScheduledTripTimes` and
`RealTimeTripTimes` are read-only after publication, realtime updates construct replacement
instances, and copy-on-write publication replaces the affected timetable/pattern in a new
`RaptorTransitData` snapshot. A request therefore retains the values from the snapshot it started
with while later requests can see a replacement snapshot.

## Validation so far

The implementation is isolated on top of official `dev-2.x` commit
`a0b62f69fc543e84687752e17dae8a66b01c5e28`. H-only tests cover multiple stops and trips, positive
and negative service-day offsets, overnight/global ordering, dwell times, and realtime snapshot
isolation. The target test and directly related timetable tests pass, as do the full application
reactor tests (8,255 tests, 0 failures, 20 skipped), formatter/checkstyle, and a shaded JAR build.

## Open questions for maintainers

1. Is retaining immutable `TripTimes` references and adding the service-day offset at accessor time
   an acceptable trade-off for request allocation reduction?
2. Are there routing or realtime code paths that mutate a `TripTimes` instance after it has become
   reachable from a published snapshot?
3. Which official SpeedTest profile and host should be used to evaluate allocation, RSS, and
   latency effects before merging this change?

The initial separate-graph preparation ran a host-side equivalent of the official Helsinki SpeedTest with the
February 2026 fixture (`-p md -n 4 -i 3 -0`, Java 26.0.2.1, `-Xmx8g`). The Maven exec plugin was
unavailable in the local dependency cache, so this is not an official CI run. Both A and H
completed, but both reported non-zero expected-result mismatches; their separately built graphs
also differed by 15 street edges. The local performance conclusion is therefore `INCONCLUSIVE`,
and this proposal does not assert a latency, RSS, or allocation improvement.

## Shared-graph validation note

The follow-up local matrix used one serialized Helsinki graph for six fresh-JVM A/H pairs and
compared every output field and cost. All 12 runs produced byte-identical result CSVs for all
408 cases. JFR sampling also showed lower temporary allocation pressure for H, especially the
`int[]` class. The sampled whole-process RSS and Routing Total latency did not improve, so the
change is presented as an allocation-pressure reduction with preserved behavior rather than a
general speed or process-memory optimization. See `H-ONLY-SHARED-GRAPH-RESULT.md` for exact
values, CI, hashes, and limitations.
