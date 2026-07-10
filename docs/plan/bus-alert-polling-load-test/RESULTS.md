# Bus Alert Polling Load Test Results

## Environment

- Test: `BusAlertDispatcherLoadIT`
- DB: PostgreSQL 17 via Testcontainers
- Worker: single `BusAlertDispatchService.dispatchDue()` caller
- External calls: fake Seoul bus API + fake Toss messenger
- Real external API calls: none

## Baseline

Command:

```bash
./gradlew test --tests watoo.grd.nextroute.application.notification.service.BusAlertDispatcherLoadIT
```

### One-second polling simulation

| Metric | Value |
|---|---:|
| ticks | 30 |
| newPerTick | 10 |
| generatedAlerts | 300 |
| sent | 300 |
| pendingBacklog | 0 |
| batchSize | 200 |
| groupCount | 10 |
| busApiLatencyMs | 0 |
| tossLatencyMs | 0 |
| avgDurationMs | 41.10 |
| maxDurationMs | 169 |
| p95DurationMs | 71 |
| p99DurationMs | 169 |
| overrunCount | 0 |
| overrunRatio | 0.00% |

Interpretation:

With no external latency and 10 new alerts per second, the current single-worker dispatcher stays within the 1-second polling budget.

## Toss Latency Sample

Command:

```bash
BUS_ALERT_POLL_SIM_TICKS=5 \
BUS_ALERT_POLL_SIM_NEW_PER_TICK=10 \
BUS_ALERT_LOAD_TOSS_LATENCY_MS=20 \
./gradlew test --tests watoo.grd.nextroute.application.notification.service.BusAlertDispatcherLoadIT.oneSecondPollingSimulation_reportsBacklogAndOverrun
```

| Metric | Value |
|---|---:|
| ticks | 5 |
| newPerTick | 10 |
| generatedAlerts | 50 |
| sent | 50 |
| pendingBacklog | 0 |
| batchSize | 200 |
| groupCount | 10 |
| busApiLatencyMs | 0 |
| tossLatencyMs | 20 |
| avgDurationMs | 339.20 |
| maxDurationMs | 421 |
| p95DurationMs | 421 |
| p99DurationMs | 421 |
| overrunCount | 0 |
| overrunRatio | 0.00% |

Interpretation:

With 10 new alerts per second and 20ms fake Toss latency, the dispatcher still stays within the 1-second polling budget.

## Larger Load Scenario

Command:

```bash
BUS_ALERT_POLL_SIM_TICKS=20 \
BUS_ALERT_POLL_SIM_NEW_PER_TICK=50 \
BUS_ALERT_POLL_SIM_GROUPS=20 \
BUS_ALERT_LOAD_BUS_API_LATENCY_MS=10 \
BUS_ALERT_LOAD_TOSS_LATENCY_MS=20 \
./gradlew test --rerun-tasks --tests watoo.grd.nextroute.application.notification.service.BusAlertDispatcherLoadIT.oneSecondPollingSimulation_reportsBacklogAndOverrun
```

| Metric | Value |
|---|---:|
| ticks | 20 |
| newPerTick | 50 |
| generatedAlerts | 1000 |
| sent | 1000 |
| pendingBacklog | 0 |
| batchSize | 200 |
| groupCount | 20 |
| busApiLatencyMs | 10 |
| tossLatencyMs | 20 |
| busApiCalls | 400 |
| tossCalls | 1000 |
| avgDurationMs | 1902.90 |
| maxDurationMs | 2003 |
| p95DurationMs | 1993 |
| p99DurationMs | 2003 |
| overrunCount | 20 |
| overrunRatio | 100.00% |

Interpretation:

The dispatcher processed all alerts and left no backlog, but every tick exceeded the 1-second polling budget.

This means the current single-worker direct-send structure cannot guarantee 1-second polling under this load shape.

## Conclusion

The current implementation is acceptable for small alert volume, but it does not reliably satisfy second-level polling accuracy as alert volume and external-call latency increase.

Observed failure mode:

```text
dispatchDue() duration > 1 second
-> next polling cycle is delayed
-> 1-second detection/notification cadence is not guaranteed
```

Primary cause:

```text
The polling loop performs external work directly:
1. bus arrival lookup
2. Toss send
3. DB state transition
```

The largest latency contributor in the larger scenario is sequential Toss sending.

## Concurrent Polling Race

Command:

```bash
./gradlew test --tests watoo.grd.nextroute.application.notification.service.BusAlertConcurrentPollingIT
```

| Metric | Value |
|---|---:|
| workers | 2 |
| pendingReads | 2 |
| claimSuccesses | 2 |
| busApiCalls | 2 |
| tossCalls | 2 |
| duplicateSends | 1 |

Interpretation:

Two concurrent workers read the same `PENDING` alert before either worker committed `PROCESSING`.

Both workers then completed the read-then-write claim path and both reached the Toss send step.

This characterizes the current claim race:

```text
findById()
-> status == PENDING
-> markProcessing()
```

Because the status predicate is not part of a single atomic `UPDATE`, concurrent workers can both treat the same row as dispatchable.

Important scope note:

This test intentionally widens the race window with a test-only state service. It is a characterization of the current read-then-write claim risk, not a production timing guarantee.

## Next Directions

1. Introduce atomic claim for multi-worker safety.
2. Move Toss sending out of the polling loop via outbox enqueue + relay.
3. Consider relay worker parallelism after outbox dedup/claim semantics are in place.
4. Reuse bus arrival cache or collector output to reduce direct bus API latency in the polling loop.
5. Add scheduler isolation as a supporting improvement, not the primary throughput fix.
