# Bus Alert Polling Load Test

## Purpose

1-second polling feasibility check for the current bus arrival alert dispatcher.

## Scope

- Single `BusAlertDispatchService.dispatchDue()` worker
- Real Postgres through Testcontainers
- Fake Seoul bus API latency
- Fake Toss messenger latency
- No real external API calls

## Files

- Test code: `src/test/java/watoo/grd/nextroute/application/notification/service/BusAlertDispatcherLoadIT.java`
- Results: add dated notes in this directory as measurements are run

