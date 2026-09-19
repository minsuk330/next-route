# H-only HTTP no-pressure run ledger (partial)

This ledger records the HTTP experiment executed on 2026-09-15/16 on the macOS host.
`A` is the upstream baseline JAR and `H` is the H-only JAR. Every run used the same
Helsinki graph `/private/tmp/otp-a-only-helsinki-4wN2sL/graph.obj` (SHA-256
`178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0`), the official
408-row corpus, endpoint `/otp/gtfs/v1/`, and `-Xms8g -Xmx8g -XX:+UseG1GC`.
Docker was not started. Runs were sequential, one JVM at a time.

## P2 correctness gate

Actual order was H1 → A1 → H2 → A2 (the plan's A1 → H1 ordering was not used).
Each run returned 408/408 HTTP 200 responses, GraphQL errors 0, transport errors 0,
and no empty plan. Pair fingerprints had zero mismatches (A1/H1 and A2/H2); repeated
A and repeated H fingerprints also had zero mismatches. Raw files are under
`/private/tmp/otp-http-no-pressure-20260915/{H1,A1,H2,A2}`.

## P3 short open-loop pilots

The generator used the same seed (`20260915`) and shuffled 408-case cycle for A/H.
RSS is the Java PID sampled at a 100 ms target; values below are measured-window
sampled statistics, not an instantaneous or retained-heap maximum. `lag` is the
scheduled-arrival-to-submit delay.

| rate | variant | requests | latency p50/p95/p99 (ms) | lag p95/max (ms) | RSS p95/peak (MiB) | Young GC | status/errors | swap |
| ---: | :---: | ---: | ---: | ---: | ---: | ---: | :--- | :--- |
| 4 RPS | A | 240 | 120.5 / 328.2 / 682.0 | 5.1 / 7.9 | 4,625 / 4,799 | n/a (pre-GC-log) | 200/0 | 0/0 |
| 4 RPS | H | 240 | 116.4 / 368.0 / 694.5 | 5.1 / 8.0 | 4,592 / 4,593 | n/a (pre-GC-log) | 200/0 | 0/0 |
| 8 RPS | A | 480 | 107.6 / 348.0 / 585.6 | 10.1 / 10.2 | 5,446 / 5,605 | 26 | 200/0 | 0/0 |
| 8 RPS | H | 480 | 102.6 / 380.2 / 667.1 | 10.1 / 11.0 | 5,241 / 5,501 | 24 | 200/0 | 0/0 |
| 12 RPS | A | 720 | 98.0 / 369.6 / 685.9 | 10.1 / 16.3 | 5,727 / 5,867 | 32 | 200/0 | 0/0 |
| 12 RPS | H | 720 | 95.5 / 391.0 / 724.2 | 10.1 / 16.7 | 5,678 / 5,707 | 30 | 200/0 | 0/0 |
| 16 RPS | A | 960 | 99.4 / 457.9 / 1,094.0 | 5.1 / 6.0 | 5,758 / 5,759 | 50 | 200/0 | 0/0 |
| 16 RPS | H | 960 | 105.2 / 427.1 / 898.1 | 9.8 / 10.2 | 6,141 / 6,169 | 38 | 200/0 | 0/0 |
| 24 RPS | A | 1,440 | 111.3 / 853.5 / 1,293.7 | 10.1 / 68.6 | 6,220 / 6,310 | 66 | 200/0 | 0/0 |
| 24 RPS | H | 1,440 | 109.3 / 730.0 / 1,184.0 | 10.1 / 46.0 | 5,851 / 5,880 | 62 | 200/0 | 0/0 |
| 32 RPS | A | 1,920 | 256.7 / 1,947.7 / 2,251.1 | 6.0 / 152.2 | 5,764 / 6,425 | 92 | 200/0 | 0/0 |
| 32 RPS | H | 1,920 | 130.3 / 1,342.0 / 1,624.3 | 5.3 / 69.9 | 6,180 / 6,210 | 78 | 200/0 | 0/0 |

The 24/32 RPS rows are saturation exploration, not no-pressure primary samples. At
32 RPS A showed clear lag/latency growth; H was less degraded in this single pair but
had higher RSS. Short pairs are exploratory and are not a PR performance claim.

## P3 ten-minute highest-common pilot

The selected 16 RPS rate was run for 600 seconds (9,600 scheduled requests) after
the GC-log option was corrected. Each run returned 9,600/9,600 HTTP 200, transport
errors 0, and A/H sequence fingerprints had zero mismatches. Both had zero Full GC,
and no `SWAP_EVENT` or monitor error. Host `memory_pressure` free percentage stayed
above 53% and `vm.swapusage used` stayed 0.00M.

| variant | latency mean/p50/p95/p99/max (ms) | lag p95/max (ms) | RSS p95/peak (MiB) | CPU mean (ps %) | Young GC | raw |
| :--- | ---: | ---: | ---: | ---: | ---: | :--- |
| A | 129.17 / 101.74 / 311.86 / 620.58 / 1,572.19 | 10.11 / 252.10 | 5,578 / 5,948 | 193.6 | 422 | `/private/tmp/otp-http-no-pressure-20260915/PILOT10-A-16rps` |
| H | 128.27 / 103.15 / 280.36 / 609.83 / 1,231.16 | 10.11 / 271.42 | 5,574 / 6,170 | 194.7 | 376 | `/private/tmp/otp-http-no-pressure-20260915/PILOT10-H-16rps` |

In this one pair H p95 latency was 10.1% lower and Young GC count 10.9% lower, while
RSS p95 was effectively unchanged (-0.07%) and sampled peak RSS was 3.7% higher.
This is a pilot observation, not a repeated statistical result.

## Current gate status

- P0/P1/P2: PASS for the scoped HTTP mapping and A/H equality gate.
- P3: observational no-swap pilot PASS on this macOS host; Linux PSI/cgroup metrics
  are unavailable, so this is not a formal cross-platform no-pressure proof.
- P4–P7: not started. The corrected client now drains completed futures during the
  open-loop schedule so interrupted runs preserve completed responses.

